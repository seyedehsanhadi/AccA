package mattecarra.accapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.Toast
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.observe
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.databinding.DashboardFragmentBinding
import mattecarra.accapp.databinding.EditChargingLimitOnceDialogBinding
import mattecarra.accapp.models.DashboardValues
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedFragment
import mattecarra.accapp.viewmodel.DashboardViewModel
import mattecarra.accapp.viewmodel.SharedViewModel
import java.util.concurrent.atomic.AtomicBoolean

class DashboardFragment : ScopedFragment()
{

    private var _binding: DashboardFragmentBinding? = null
    private val binding get() = _binding!!

    private val LOG_TAG = "DashboardFragment"

    private val PERMISSION_REQUEST: Int = 0
    private val ACC_CONFIG_EDITOR_REQUEST: Int = 1
    private val ACC_PROFILE_CREATOR_REQUEST: Int = 2
    private val ACC_PROFILE_EDITOR_REQUEST: Int = 3
    private val ACC_PROFILE_SCHEDULER_REQUEST: Int = 4

    companion object
    {
        fun newInstance() = DashboardFragment()
    }

    private val mViewModel: DashboardViewModel by activityViewModels()
    private lateinit var mDashboardConfigFrg: DashboardConfigFragment
    private lateinit var configViewModel: SharedViewModel
    private lateinit var preferences: Preferences
    private var mIsDaemonRunning: Boolean? = null

    // B12 charging-health warning: count consecutive polls where charging looks broken.
    // The card only appears once the condition is SUSTAINED (>= 2 ticks, ~4s) so a single
    // transient tick during a normal pause/cooldown switch-over never trips it.
    private var mHealthWarnTicks: Int = 0
    private val HEALTH_WARN_MIN_TICKS = 2

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        _binding = DashboardFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView()
    {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onViewCreated()")

        super.onViewCreated(view, savedInstanceState)

        // Initialise preferences up front (the observer below reads it). The view
        // exists here so the context is valid; doing it inside activity?.let could
        // leave it uninitialised and crash the observer with a property-access error.
        preferences = Preferences(view.context)

        //-----------------------------------------------------------------

        val transaction = activity?.supportFragmentManager?.beginTransaction()
        mDashboardConfigFrg = DashboardConfigFragment.newInstance()
        transaction?.replace(R.id.current_profile, mDashboardConfigFrg)
        transaction?.commit()

        //-----------------------------------------------------------------

        mViewModel.getDashboardValues().observe(viewLifecycleOwner) { dash ->
            // Set Status Card text
            dash.daemon?.let { daemon -> setAccdStatusUi(daemon) }

            // Battery/Charge details
            binding.dashBatteryCapacityPBar.progress = dash.batteryInfo.capacity

            // Prefer the rc9+ `acca --state` snapshot when present: its status/measuredClass and
            // signed current are correct even while ACC is cutting (the legacy `acca -i` reads
            // unplugged-while-plugged and carries no polarity). Fall back to batteryInfo otherwise.
            val state = dash.state
            if (state != null)
            {
                // measuredClass is the real measured behaviour (charging/discharging/cut/bypass);
                // status is the kernel label. Show measuredClass when present, else status.
                val label = state.measuredClass.ifBlank { state.status }
                    .replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                binding.dashBatteryStatusTextView.text = label

                val charging = state.measuredClass.equals("charging", true) ||
                        (state.measuredClass.isBlank() && state.status.equals("Charging", true))
                binding.dashBatteryChargingSpeedTextView.text =
                    if (charging) getString(R.string.info_charging_speed) else getString(R.string.info_discharging_speed)

                binding.dashChargingSpeedTextView.text =
                    formatCurrentFromState(state.signedCurrentMilliAmps())

                // Manual-lock badge (rc8 userLocked): ACC will not auto-replace a user-pinned switch.
                binding.dashManualLockTextView.visibility = if (state.userLocked) View.VISIBLE else View.GONE
            }
            else
            {
                binding.dashBatteryStatusTextView.text = getString(R.string.info_status_extended, dash.batteryInfo.status, dash.batteryInfo.chargeType)

                binding.dashBatteryChargingSpeedTextView.text = if (dash.batteryInfo.isCharging()) getString(R.string.info_charging_speed) else getString(R.string.info_discharging_speed)

                val plus = if (Acc.instance.version < 202107280) dash.batteryInfo.isCharging() else true
                binding.dashChargingSpeedTextView.text = dash.batteryInfo.getCurrentNow(preferences.currentInputUnitOfMeasure, preferences.currentOutputUnitOfMeasure, plus, true)

                binding.dashManualLockTextView.visibility = View.GONE
            }

            binding.dashBatteryTemperatureTextView.text = dash.batteryInfo.getTemperature(preferences.temperatureOutputUnitOfMeasure, true)
            binding.dashBatteryHealthTextView.text = dash.batteryInfo.health
            binding.dashBatteryVoltageTextView.text = dash.batteryInfo.getVoltageNow(preferences.voltageInputUnitOfMeasure, preferences.voltageOutputUnitOfMeasure, true)

            evaluateHealthWarning(dash)
        }

        activity?.let { it ->

            preferences = Preferences(it)
            configViewModel = ViewModelProvider(it).get(SharedViewModel::class.java)

            binding.dashResetBatteryStatsButton.setOnClickListener {
                launch { try { Acc.instance.resetBatteryStats() } catch (e: Exception) { } }
            }

            binding.dashEditCargingLimitOnceButton.setOnClickListener {
                val dialog = EditChargingLimitOnceDialogBinding.inflate(layoutInflater)
                MaterialDialog(it.context).show {
                    title(R.string.edit_charging_limit_once_button)
                    message(R.string.edit_charging_limit_once_dialog_msg)
                    cancelOnTouchOutside(false)
                    customView(view=dialog.root)
                    positiveButton(R.string.apply) {
                        launch {
                            try {
                                val limit = getCustomView().findViewById<NumberPicker>(R.id.charging_limit).value
                                Acc.instance.setChargingLimitForOneCharge(limit)
                                Toast.makeText(context, getString(R.string.done_applied_charge_limit, limit), Toast.LENGTH_LONG).show()
                            } catch (e: Exception) { }
                        }
                    }
                    negativeButton(android.R.string.cancel) {
                        launch {
                            context?.let { Toast.makeText(it, R.string.charge_limit_not_applied, Toast.LENGTH_LONG).show() }   // A8: guard nullable fragment context after the suspend point
                        }
                    }
                }

                val picker = dialog.chargingLimit
                picker.maxValue = 100
                picker.minValue = 20
                picker.value = 100
            }
        }

        binding.dashDaemonToggleButton.setOnClickListener {
            // If the daemon is currently running, the tap will stop it -> confirm first so an
            // accidental tap can't silently disable the charge limit. Starting it is risk-free,
            // so skip the dialog in that direction.
            if (mIsDaemonRunning == true)
            {
                MaterialDialog(view.context).show {
                    title(R.string.confirm_stop_daemon_title)
                    message(R.string.confirm_stop_daemon_message)
                    positiveButton(R.string.stop) { performDaemonToggle() }
                    negativeButton(android.R.string.cancel)
                }
            }
            else performDaemonToggle()
        }

        binding.dashDaemonRestartButton.setOnClickListener {
            Toast.makeText(context, R.string.wait, Toast.LENGTH_LONG).show()

            binding.dashDaemonToggleButton.isEnabled = false
            binding.dashDaemonRestartButton.isEnabled = false

            launch {
                binding.dashDaemonToggleButton.isEnabled = false
                binding.dashDaemonRestartButton.isEnabled = false

                withContext(Dispatchers.IO) {
                    Acc.instance.accRestartDaemon()
                }

                delay(3000)

                // View may have been torn down during the delay; guard binding access.
                _binding?.let { b ->
                    b.dashDaemonToggleButton.isEnabled = true
                    b.dashDaemonRestartButton.isEnabled = true
                }
            }
        }

        mViewModel.getDashboardValues().observe(viewLifecycleOwner, Observer { d ->
            toggleAccdStatusUi(d.daemon)
            mIsDaemonRunning = d.daemon
        })
    }

    /**
     * Formats an already-signed current (milliamps, from `acca --state`) into the user's
     * chosen output unit, mirroring BatteryInfo.getCurrentNow's "x.xxx A" / "x mA" format.
     * The sign is already correct (normalised by polarity/units), so no `positive` flag here.
     */
    private fun formatCurrentFromState(signedMilliAmps: Float): String
    {
        return if (preferences.currentOutputUnitOfMeasure == mattecarra.accapp.CurrentUnit.A)
            String.format("%.3f", signedMilliAmps / 1000f) + " A"
        else
            signedMilliAmps.toInt().toString() + " mA"
    }

    private fun toggleAccdStatusUi(running: Boolean?)
    {
        when (mIsDaemonRunning)
        {
            null ->
            {
                setAccdStatusUi(running)
            }
            false ->
            {
                if (running != null && running) setAccdStatusUi(running)
            }
            true ->
            {
                if (running != null && !running) setAccdStatusUi(running)
            }
        }
    }

    /**
     * Toggle daemon body, factored out of the click handler so the Stop-confirm dialog can
     * gate it without duplicating the run. Always-on; the confirm only sits in front of it.
     */
    private fun performDaemonToggle()
    {
        Toast.makeText(context, R.string.wait, Toast.LENGTH_LONG).show()

        launch {
            val finished = AtomicBoolean(false)
            val stopDaemon = Acc.instance.isAccdRunning()

            binding.dashDaemonToggleButton.isEnabled = false
            binding.dashDaemonRestartButton.isEnabled = false

            val observer = Observer<DashboardValues> { daemonInfo ->
                if (daemonInfo?.daemon == !stopDaemon && !finished.getAndSet(true))
                { //if accDeamon status is the opposite of the status it had before the action -> change had effect
                    finished.set(true)

                    // The view may be gone by the time this observer fires; guard.
                    _binding?.let { b ->
                        b.dashDaemonToggleButton.isEnabled = true
                        b.dashDaemonRestartButton.isEnabled = true
                    }
                }
            }

            mViewModel.getDashboardValues().observe(viewLifecycleOwner, observer)

            withContext(Dispatchers.IO) {
                if (stopDaemon) Acc.instance.abcStopDaemon()
                else Acc.instance.abcStartDaemon()
            }

            delay(5000)

            mViewModel.getDashboardValues().removeObserver(observer)

            if (!finished.getAndSet(true))
            {
                // View may have been torn down during the delay; guard binding access.
                _binding?.let { b ->
                    b.dashDaemonToggleButton.isEnabled = true
                    b.dashDaemonRestartButton.isEnabled = true
                }
            }
        }
    }

    private fun setAccdStatusUi(running: Boolean?)
    {
        if (running == null) return

        // Bail if the view/activity is gone: binding access and requireActivity()
        // below would otherwise throw after detach.
        if (_binding == null || !isAdded) return

        if (running)
        {
            // Hide progress bar
            binding.dashAccdStatusPb.visibility = View.GONE
            // Show and change icon
            binding.dashAccdStatusImageView.visibility = View.VISIBLE
            binding.dashAccdStatusFrameLay.setBackgroundColor(getColor(requireActivity().baseContext, R.color.colorSuccessful))
            binding.dashAccdStatusImageView.setImageResource(R.drawable.ic_outline_check_circle_24px)
            binding.dashAccdStatusTextView.setText(R.string.acc_daemon_status_running)
            // Enable buttons
            binding.dashDaemonRestartButton.isEnabled = true
            binding.dashDaemonToggleButton.isEnabled = true
            binding.dashDaemonToggleButton.setIconResource(R.drawable.ic_outline_stop_24px)
            binding.dashDaemonToggleButton.setText(R.string.stop)
        }
        else
        {
            // Hide progress bar
            binding.dashAccdStatusPb.visibility = View.GONE
            // Show and change icon
            binding.dashAccdStatusImageView.visibility = View.VISIBLE
            binding.dashAccdStatusFrameLay.setBackgroundColor(getColor(requireActivity().baseContext, R.color.color_error))
            binding.dashAccdStatusImageView.setImageResource(R.drawable.ic_outline_error_outline_24px)
            binding.dashAccdStatusTextView.setText(R.string.acc_daemon_status_not_running)
            // Enable buttons
            binding.dashDaemonRestartButton.isEnabled = true
            binding.dashDaemonToggleButton.isEnabled = true
            binding.dashDaemonToggleButton.setIconResource(R.drawable.ic_outline_play_arrow_24px)
            binding.dashDaemonToggleButton.setText(R.string.start)
        }
    }

    /**
     * B12: warn that charging may be broken — but ONLY when ALL of these hold, and only after
     * the condition has been SUSTAINED across [HEALTH_WARN_MIN_TICKS] polls:
     *   - the ACC daemon is running
     *   - the charger is plugged in (status == "Not charging": Android's plugged-but-halted state)
     *   - the battery is NOT charging (implied by the status above)
     *   - capacity is below the configured pause level
     *   - charging is not already complete (isChargeDone)
     *   - ACC is NOT intentionally holding charge (isChargeDisabled) -> excludes the normal
     *     pause-capacity / cooldown / thermal pauses
     *   - the battery is below the thermal-pause temperature (excludes a max-temp pause)
     *
     * It reads only the already-polled [BatteryInfo] plus the cached config (no extra root
     * calls). "Discharging" is deliberately NOT treated as plugged, so a phone simply running
     * on battery can never trip the card. Everything is best-effort: if the config is not
     * loaded yet we do not warn.
     */
    private fun evaluateHealthWarning(dash: DashboardValues)
    {
        if (_binding == null || !isAdded) return
        // rc4: the "charging may be broken" card is disabled. It false-positives on native-limit
        // and bypass devices, where plugged-but-not-charging below the pause level is the firmware's
        // NORMAL hold/hysteresis (e.g. a Pixel holding in its charge_start..charge_stop band), not a
        // fault. ACC's own daemon already warns accurately -- only on a real overcharge past the
        // limit -- so this app-side guess is redundant and was misleading.
        binding.dashHealthWarningCard.visibility = View.GONE
    }

    private fun computeHealthWarn(dash: DashboardValues): Boolean
    {
        // Daemon must be running.
        if (dash.daemon != true) return false

        val info = dash.batteryInfo

        // Plugged but halted. NOT_CHARGING is Android's "power attached, battery not charging"
        // state; "Charging" obviously means it works, "Discharging" means unplugged/draining.
        if (info.status != "Not charging") return false

        // Already full / done -> normal, never warn.
        if (info.isChargeDone) return false

        // ACC intentionally holding charge (pause-cap / cooldown / thermal) -> normal pause.
        if (info.isChargeDisabled) return false

        // Need the config to know the pause level + thermal ceiling; if it is not loaded yet
        // we stay silent rather than risk a false positive.
        if (!::configViewModel.isInitialized) return false

        val pause = configViewModel.getAccConfigValue { it.configCapacity.pause } ?: return false
        // Above (or at) the pause level, "not charging" is exactly what ACC should do.
        if (info.capacity < 0 || info.capacity >= pause) return false

        // Exclude a thermal pause: if temperature is at/above the configured max, not charging
        // is expected. Temperature is -1 when unknown -> skip this guard then.
        val maxTemp = configViewModel.getAccConfigValue { it.configTemperature.maxTemperature }
        if (maxTemp != null && info.temperature >= 0 && info.temperature >= maxTemp) return false

        return true
    }

}
