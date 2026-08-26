package mattecarra.accapp.fragments

import android.content.Intent
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
import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.StateFormat
import mattecarra.accapp.models.isChargingNow
import mattecarra.accapp.models.DashboardValues
import mattecarra.accapp.models.chargeStatusWord
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

    // D1 (6.5.1/2.0.1): last good structured state + consecutive-miss counter. One shell
    // hiccup used to flip the card to the legacy "Status--ChargeType" grammar for a tick
    // (the field-reported "Not Charging--Unknown" flicker). Re-render the last good state
    // for up to two missed ticks; only a SUSTAINED miss falls back to the legacy formatter.
    private var mLastState: AccState? = null
    private var mStateNullStreak: Int = 0

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

        // Use the CHILD manager and commitAllowingStateLoss.
        //
        // This crashed the app outright: "IllegalStateException: Can not perform this action
        // after onSaveInstanceState". onViewCreated can run after the host activity has saved
        // its state -- launching while the notification shade is open reproduces it -- and
        // commit() throws there rather than degrading. Caught on a Pixel.
        //
        // Two changes. childFragmentManager is the correct owner: this fragment is nested inside
        // the dashboard, so its lifecycle should follow the parent fragment rather than the
        // activity, which is what made the state-loss window reachable at all.
        // commitAllowingStateLoss is safe for this transaction specifically -- it rebuilds a
        // panel that is fully repopulated from SharedViewModel.config on every change, so there
        // is no user input or navigation state to lose, only a view that gets re-inflated.
        val transaction = childFragmentManager.beginTransaction()
        mDashboardConfigFrg = DashboardConfigFragment.newInstance()
        transaction.replace(R.id.current_profile, mDashboardConfigFrg)
        transaction.commitAllowingStateLoss()

        //-----------------------------------------------------------------

        mViewModel.getDashboardValues().observe(viewLifecycleOwner) { dash ->
            // Set Status Card text
            dash.daemon?.let { daemon -> setAccdStatusUi(daemon) }

            // Battery/Charge details. The whole app follows the "Battery percentage source" setting:
            // ACC's coulomb capacity (reflects your Capacity Mask) when set to "acc", else the phone's
            // own System level. Falls back to System whenever the ACC snapshot is missing/invalid.
            val accCap = dash.state?.capacityPct?.takeIf { it in 0..100 }
            // batteryInfo.capacity is -1 when both capacity regexes miss the `acca -i` output;
            // that value used to reach the ProgressBar unfiltered. Clamp to a legal percentage.
            val shownCapacity = (if (preferences.chargeMeterBatterySource == "acc" && accCap != null) accCap else dash.batteryInfo.capacity)
                .coerceIn(0, 100)
            binding.dashBatteryCapacityPBar.progress = shownCapacity

            // Prefer the rc9+ `acca --state` snapshot when present: its status/measuredClass and
            // signed current are correct even while ACC is cutting (the legacy `acca -i` reads
            // unplugged-while-plugged and carries no polarity). Fall back to batteryInfo otherwise.
            val state = dash.state
            if (state != null)
            {
                mLastState = state
                mStateNullStreak = 0
                renderStateCard(state)
            }
            else if (mStateNullStreak < 2 && mLastState != null)
            {
                mStateNullStreak++
                renderStateCard(mLastState!!)
            }
            else
            {
                mStateNullStreak++
                binding.dashBatteryStatusTextView.text = getString(R.string.info_status_extended, dash.batteryInfo.status, dash.batteryInfo.chargeType)

                binding.dashBatteryChargingSpeedTextView.text = if (dash.batteryInfo.isCharging()) getString(R.string.info_charging_speed) else getString(R.string.info_discharging_speed)

                val plus = if (Acc.instance.version < 202107280) dash.batteryInfo.isCharging() else true
                binding.dashChargingSpeedTextView.text = dash.batteryInfo.getCurrentNow(preferences.currentInputUnitOfMeasure, preferences.currentOutputUnitOfMeasure, plus, true)

                binding.dashManualLockTextView.visibility = View.GONE
                binding.dashChargerTextView.visibility = View.GONE
            }

            // Single source of truth: when the `acca --state` snapshot rendered this card, take
            // volts and temperature from it too. Reading them from batteryInfo (the legacy
            // `acca -i` scrape) while amps/status came from --state left the card split-brained.
            // Health has no --state field, so it stays on batteryInfo.
            val shown = if (state != null) state else mLastState.takeIf { mStateNullStreak <= 2 }
            if (shown != null) {
                binding.dashBatteryTemperatureTextView.text =
                    formatTemperatureFromState(shown.tempDeciC)
                binding.dashBatteryVoltageTextView.text =
                    formatVoltageFromState(shown.voltageRaw)
            } else {
                binding.dashBatteryTemperatureTextView.text = dash.batteryInfo.getTemperature(preferences.temperatureOutputUnitOfMeasure, true)
                binding.dashBatteryVoltageTextView.text = dash.batteryInfo.getVoltageNow(preferences.voltageInputUnitOfMeasure, preferences.voltageOutputUnitOfMeasure, true)
            }
            binding.dashBatteryHealthTextView.text = dash.batteryInfo.health

            evaluateHealthWarning(dash)
        }

        activity?.let { it ->

            preferences = Preferences(it)
            configViewModel = ViewModelProvider(it).get(SharedViewModel::class.java)

            // Straight to the finder. It runs the bundled AMPS tester itself, so there is nothing
            // in the config editor it needs on the way in.
            binding.dashFindSwitchButton.setOnClickListener { _ ->
                startActivity(Intent(it, mattecarra.accapp.activities.SwitchFinderActivity::class.java))
            }

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
                    context?.let { Preferences(it).accdUserStopped = false }
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
    private fun formatCurrentFromState(signedMilliAmps: Float): String =
        StateFormat.current(signedMilliAmps, preferences.currentOutputUnitOfMeasure)

    // --state reports deci-Celsius (230 = 23.0C), the same scale batteryInfo exposes after
    // its own /10, so honour the user's output unit exactly as getTemperature() does.
    private fun formatTemperatureFromState(tempDeciC: Int): String =
        StateFormat.temperature(tempDeciC, preferences.temperatureOutputUnitOfMeasure)

    // voltage_raw is microvolts on every device seen so far, but ACC does not promise it:
    // fold a millivolt-scale reading up rather than printing 0.004 V.
    private fun formatVoltageFromState(voltageRaw: Long): String =
        StateFormat.voltage(voltageRaw, preferences.voltageOutputUnitOfMeasure)

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
                // Remember a DELIBERATE stop: the plug-in daemon guard must not resurrect a daemon
                // the user chose to stop (and a manual start lifts that choice again).
                context?.let { Preferences(it).accdUserStopped = stopDaemon }
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
    /**
     * One formatter for the structured-state card (D1). measuredClass is the real measured
     * behaviour; status is the kernel label (which LIES on some devices - the bramble film
     * showed "Charging" through 10 minutes of measured drain). The label speaks the canonical
     * vocabulary: Bypass (plugged, battery idle), Standby (unplugged), Draining [to N%].
     */
    private fun statusLabel(state: AccState): String
    {
        val mc = state.measuredClass.lowercase()
        if (state.nativeEnabled && state.nativeStopLevel in 1..99 &&
            state.capacityPct > state.nativeStopLevel && (mc == "discharging" || mc == "drain"))
            return getString(R.string.status_draining_to, state.nativeStopLevel)
        return when (chargeStatusWord(state.plugged, state.measuredClass, state.status,
            state.signedCurrentMilliAmps())) {
            "Discharging" -> getString(R.string.status_discharging)
            "Idle" -> getString(R.string.status_idle)
            "Draining" -> getString(R.string.status_draining)
            "Bypass" -> getString(R.string.status_bypass)
            else -> if (state.status.equals("Full", true)) state.status else getString(R.string.charge_meter_charging)
        }
    }

    private var mLastConfigSignature: String? = null

    private fun renderStateCard(state: AccState)
    {
        // --state carries ACC's own config every poll. When it stops matching what the settings
        // card was fed, something outside AccA changed it, so re-read once and let the existing
        // observer update the card. Costs one root read at the moment of an actual change, and
        // nothing at all otherwise.
        state.configSignature?.let { sig ->
            if (mLastConfigSignature != null && mLastConfigSignature != sig)
                if (::configViewModel.isInitialized) configViewModel.reloadConfigFromAcc()
            mLastConfigSignature = sig
        }
        // The level existed only as a progress bar; no figure appeared anywhere on the card.
        binding.dashBatteryStatusTextView.text =
            if (state.capacityPct in 0..100)
                getString(R.string.dash_status_with_level, statusLabel(state), state.capacityPct)
            else statusLabel(state)
        binding.dashBatteryStatusTextView.contentDescription = getString(R.string.status_hint)

        val shownMa = state.signedCurrentMilliAmps()
        // Precedence, and the order matters: measuredClass is what the daemon MEASURED, kernel
        // status is the thing measuredClass exists to correct -- this file's own comment above says
        // `acca -i` "reads unplugged-while-plugged", and AMPS logged phones reporting Charging
        // through ten minutes of measured drain. An earlier version of this block asked status
        // first, so those phones read "Charging Speed" while the battery emptied. The 80mA
        // threshold stays as the last tie-break for a phone that reports neither.
        val charging = isChargingNow(state.measuredClass, state.status, shownMa)
        binding.dashBatteryChargingSpeedTextView.text =
            if (charging) getString(R.string.info_charging_speed) else getString(R.string.info_discharging_speed)

        val vbat = if (state.voltageRaw >= 100000L) (state.voltageRaw / 1000L).toInt() else state.voltageRaw.toInt()
        // The wattage carries the CURRENT's sign. Taking abs() here printed "-557 mA  .  2.1 W",
        // two numbers describing the same flow disagreeing about its direction, and the positive
        // watts is the one that reads like charging. shownMa is already normalised for this
        // device's polarity, so the sign it carries is the answer for both.
        val battW = AccState.batteryWatts(shownMa, state.voltageRaw)
        // Two wattages appear on this card -- this one and the charger input below -- and they
        // differ by conversion loss. Unlabelled they read as a contradiction (5.0 W vs 7 W),
        // so each states its side. The row label is "To battery:".
        binding.dashChargingSpeedTextView.text =
            if (kotlin.math.abs(battW) >= 0.1f)
                getString(R.string.dash_batt_flow, formatCurrentFromState(shownMa),
                          String.format("%.1f W", battW))
            else formatCurrentFromState(shownMa)

        // This row carried only the manual-lock note. On a firmware-limit phone the levels ACC
        // is actually enforcing (native start..stop) appeared NOWHERE on the dashboard, so the
        // card could not answer "what is holding my charge". Show both facts when both apply.
        val enforcement = listOfNotNull(
            if (state.userLocked) getString(R.string.manual_lock_protected) else null,
            if (state.nativeEnabled && state.nativeStopLevel in 1..100 && state.nativeStartLevel in 1..100)
                getString(R.string.dash_native_limit, state.nativeStartLevel, state.nativeStopLevel)
            else null
        )
        binding.dashManualLockTextView.text = enforcement.joinToString("  ·  ")
        binding.dashManualLockTextView.visibility =
            if (enforcement.isEmpty()) View.GONE else View.VISIBLE
        val vin = state.inputVoltageMv
        val iin = state.inputCurrentMa
        val watts = state.chargeWatts
        val clsRes = when (state.chargeClass) {
            "slow" -> R.string.charge_class_slow
            "standard" -> R.string.charge_class_standard
            "fast" -> R.string.charge_class_fast
            "superfast" -> R.string.charge_class_superfast
            "hyper" -> R.string.charge_class_hyper
            else -> null
        }
        val reasonRes = when (state.chargeReason) {
            "user_limit" -> R.string.charge_reason_user_limit
            "thermal" -> R.string.charge_reason_thermal
            "taper" -> R.string.charge_reason_taper
            else -> null
        }
        val line: String? = when {
            !charging || watts == null || clsRes == null -> null
            !state.chargeApprox && vin != null && vin > 0 && iin != null && iin > 50 && vbat in 3000..4600 -> {
                getString(
                    R.string.dash_charge_fmt,
                    String.format("%.1f V", vin / 1000f),
                    String.format("%.2f", iin / 1000f),
                    watts,
                    getString(clsRes)
                )
            }
            else -> getString(R.string.dash_charge_fmt_approx, getString(clsRes), watts)
        }
        if (line != null) {
            binding.dashChargerTextView.text =
                if (reasonRes != null) "$line, ${getString(reasonRes)}" else line
            binding.dashChargerTextView.visibility = View.VISIBLE
        } else {
            binding.dashChargerTextView.visibility = View.GONE
        }
    }

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

    // computeHealthWarn() was removed: evaluateHealthWarning() hard-hides the card
    // (rc4, documented false positives on native-limit devices), so this scorer was
    // unreachable. The hiding decision stands; only the dead code is gone.

}
