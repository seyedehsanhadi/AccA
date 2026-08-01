package mattecarra.accapp.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.afollestad.materialdialogs.list.listItems
import com.afollestad.materialdialogs.list.toggleItemChecked
import com.afollestad.materialdialogs.list.updateListItemsSingleChoice
import com.topjohnwu.superuser.Shell
import it.sephiroth.android.library.xtooltip.ClosePolicy
import it.sephiroth.android.library.xtooltip.Tooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.acc.VerifiedSwitch
import mattecarra.accapp.databinding.ActivityAccConfigEditorBinding
import mattecarra.accapp.databinding.AddChargingSwitchDialogBinding
import mattecarra.accapp.databinding.ContentAccConfigEditorBinding
import mattecarra.accapp.dialogs.powerLimitDialog
import mattecarra.accapp.dialogs.progress
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.AccaProfile
import mattecarra.accapp.models.ProfileEnables
import mattecarra.accapp.utils.Constants
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedAppActivity
import mattecarra.accapp.viewmodel.AccConfigEditorViewModel
import mattecarra.accapp.viewmodel.AccConfigEditorViewModelFactory

class AccConfigEditorActivity : ScopedAppActivity(),
    NumberPicker.OnValueChangeListener, CompoundButton.OnCheckedChangeListener
{
    private lateinit var content: ContentAccConfigEditorBinding
    private lateinit var viewModel: AccConfigEditorViewModel
    private lateinit var mUndoMenuItem: MenuItem
    private lateinit var mPreferences: Preferences
    private lateinit var initConfig: AccConfig
    private var initEnables: mattecarra.accapp.models.ProfileEnables? = null
    private var accConfigOnly: Boolean = false

    // Cached verified-switch artifact (read once on screen load). Used to populate the
    // "Recommended charging switch" card and to flag a drain-class switch in the capacity
    // picker (B18). Null until detect() returns; only Verified/NeedsTest are kept.
    private var verifiedSwitch: VerifiedSwitch? = null

    /**
     * Validates an [AccConfig] against the daemon's write-config.sh constraints. Returns null
     * when the config is acceptable, else a user-facing error string. Without this the daemon
     * silently coerces out-of-band values back to its defaults, so the user's edit is lost.
     *
     * configTemperature.pause is resume_temp in °C (legacy field name); configCapacity uses
     * shutdown < resume < pause, all percent 0..100. ACC's write-config coerces pause=101 -> 80,
     * so AccA must keep pause in ACC's percent domain; section on/off is the eCapacity flag, not 101.
     */
    private fun validateConfig(c: AccConfig): String?
    {
        val t = c.configTemperature                 // coolDownTemperature, maxTemperature, pause(=resume_temp), shutdown
        val max = t.maxTemperature; val cd = t.coolDownTemperature; val res = t.pause; val shutdown = t.shutdown
        if (max !in 20..60) return getString(R.string.err_max_temp_range)
        if (max - cd < 3)   return getString(R.string.err_cooldown_gap)
        if (res >= max)     return getString(R.string.err_resume_lt_max)
        if (max - res > 10) return getString(R.string.err_resume_window)
        if (cd < res)       return getString(R.string.err_temp_order)
        // shutdown_temp: match ACC's write-config.sh rule exactly -- shutdown must be in
        // [max(max_temp, 40) .. 70]. The cutoff sits at or above the max (pause) temperature.
        // ACC accepts shutdown == max_temp (e.g. 50/50), so AccA must too; the old max+3 / floor-50
        // tightening rejected valid ACC configs and blocked saves the daemon would have accepted.
        if (shutdown !in maxOf(max, 40)..70) return getString(R.string.err_shutdown_temp_range)

        val cap = c.configCapacity                  // shutdown < resume < pause; percent 0..100 OR mV 3001..5000
        if (cap.pause > 100) {                       // ACC mV-capacity domain (voltage thresholds)
            if (cap.pause !in 3001..5000) return getString(R.string.err_cap_pause_mv)
            if (cap.resume !in 3001..5000) return getString(R.string.err_cap_mv_domain)
            if (cap.shutdown in 1..3000 || cap.shutdown > 5000) return getString(R.string.err_cap_mv_domain)
        } else {
            if (cap.pause !in 0..100) return getString(R.string.err_pause_pct)
        }
        if (cap.resume >= cap.pause) return getString(R.string.err_cap_resume_lt_pause)
        if (cap.shutdown >= cap.resume) return getString(R.string.err_cap_shutdown_lt_resume)
        return null
    }

    private fun returnResults()
    {
        val err = validateConfig(viewModel.profile.accConfig)
        if (err != null)
        {
            MaterialDialog(this).show {
                title(R.string.invalid_config)
                message(text = err)
                positiveButton(android.R.string.ok)
            }
            return   // do NOT finish(); keep the editor open so the user can fix it
        }

        if (accConfigOnly)  // FIX OUT if load ONLY ACC Config
        {
            if (!viewModel.enables.eCoolDown) viewModel.coolDown = null
            if (!viewModel.enables.eVoltage) viewModel.voltageLimit = AccConfig.ConfigVoltage(null, null)
            if (!viewModel.enables.eCurrMax) viewModel.currentMaxLimit = null
            if (!viewModel.enables.eRunOnBoot) viewModel.onBoot = null
            if (!viewModel.enables.eRunOnPlug) viewModel.onPlug = null
        }

        val returnIntent = Intent()
        returnIntent.putExtra(Constants.PROFILE_ID_KEY, intent.getIntExtra(Constants.PROFILE_ID_KEY, -1))
        returnIntent.putExtra(Constants.ACC_HAS_CHANGES, viewModel.unsavedChanges)
        returnIntent.putExtra(Constants.ACC_CONFIG_KEY, viewModel.profile.accConfig)
        returnIntent.putExtra(Constants.PROFILE_CONFIG_KEY, viewModel.profile)
        setResult(Activity.RESULT_OK, returnIntent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        val binding = ActivityAccConfigEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        content = binding.contentAccConfigEditor

        // Load preferences
        mPreferences = Preferences(this)

        setSupportActionBar(binding.accConfEditorToolbar)
        supportActionBar?.title = intent?.getStringExtra(Constants.TITLE_KEY) ?: getString(R.string.acc_config_editor)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Saved-state / intent extras are deserialized below. A malformed or
        // mistyped extra (wrong class, null) must NOT crash the editor, so every
        // cast is null-safe (`as?`) and falls back to a fresh profile/config.
        val profile = when // load profile from intent
        {
            savedInstanceState?.containsKey(Constants.PROFILE_CONFIG_KEY) == true ->
                savedInstanceState.getSerializable(Constants.PROFILE_CONFIG_KEY) as? AccaProfile

            intent.hasExtra(Constants.PROFILE_CONFIG_KEY) ->
                intent.getSerializableExtra(Constants.PROFILE_CONFIG_KEY) as? AccaProfile

            else -> null
        } ?: run {
            accConfigOnly = true
            AccaProfile(-1, "", AccConfig(), ProfileEnables())
        }

        val config = when // load config from intent or current config
        {
            savedInstanceState?.containsKey(Constants.ACC_CONFIG_KEY) == true ->
                savedInstanceState.getSerializable(Constants.ACC_CONFIG_KEY) as? AccConfig

            intent.hasExtra(Constants.ACC_CONFIG_KEY) ->
                intent.getSerializableExtra(Constants.ACC_CONFIG_KEY) as? AccConfig

            else -> null
        }

        if (config != null)
        {
            // Config came from intent / saved state -> no root call needed,
            // safe to finish setup synchronously on the main thread.
            finishSetup(profile, config)
            LogExt().d(javaClass.simpleName, "onCreate(): accConfigOnly=$accConfigOnly, profile=$profile")
        }
        else
        {
            // No config supplied: must read it via root, which is a blocking shell
            // call -> NEVER run it on the main thread (ANR). Read on IO, then
            // populate the UI back on Main. Keep the activity responsive meanwhile.
            launch {
                val readConfig = withContext(Dispatchers.IO)
                {
                    try
                    {
                        Acc.instance.readConfig()
                    }
                    catch (ex: Exception)
                    {
                        ex.printStackTrace()
                        // readDefaultConfig() is also a root call and can throw; if even
                        // that fails, fall back to in-memory defaults so we cannot crash.
                        try { Acc.instance.readDefaultConfig() } //if mAccConfig is null I use default mAccConfig values.
                        catch (ex2: Exception) { ex2.printStackTrace(); null }
                    }
                }

                if (isFinishing || isDestroyed) return@launch

                if (readConfig == null) showConfigReadError()

                finishSetup(profile, readConfig ?: AccConfig())
                LogExt().d(javaClass.simpleName, "onCreate(): accConfigOnly=$accConfigOnly, profile=$profile")
            }
        }
    }

    /**
     * Finishes editor setup once an [AccConfig] is available. Must run on the
     * main thread (touches UI / ViewModel / binding).
     */
    private fun finishSetup(profile: AccaProfile, config: AccConfig)
    {
        if (accConfigOnly) profile.accConfig = config
        initConfig = profile.accConfig.copy()

        viewModel = ViewModelProvider(this, AccConfigEditorViewModelFactory(application, profile))
            .get(AccConfigEditorViewModel::class.java)

        initUi()

        // Snapshot the enable states AFTER initUi (which derives them from the loaded config) so
        // recomputeDirty() can tell a real change from a transient toggle that was reverted.
        initEnables = viewModel.enables

        viewModel.clearHistory()

        // On the async (no-extra) path, onCreateOptionsMenu already ran BEFORE viewModel was
        // initialised, so the undo item was disabled and its observer never wired. Rebuild the
        // menu now that viewModel exists so undo works on the edit-current-config path too.
        invalidateOptionsMenu()
    }

    // unsavedChanges is a one-way latch (any setter flips it true), so a transient toggle that is
    // reverted - e.g. enabling Apply on Boot then cancelling the command dialog - left a phantom
    // "unsaved changes?" prompt on exit. After such a revert, recompute the flag against the load
    // snapshot: dirty only if a config value or an enable state actually differs from load.
    private fun recomputeDirty()
    {
        if (::viewModel.isInitialized && ::initConfig.isInitialized)
            viewModel.unsavedChanges =
                viewModel.profile.accConfig != initConfig || viewModel.enables != initEnables
    }

    private fun initUi()
    {
        viewModel.observeEnables(this, Observer
        {
            content.capacitySwitchEnabled.isChecked = it.eCapacity
            content.voltcontrolSwitchEnabled.isChecked = it.eVoltage || it.eCurrMax
            content.tempSwitchEnabled.isChecked = it.eTemperature
            content.cooldownSwitchEnabled.isChecked = it.eCoolDown
            content.applyOnBootSwitchEnabled.isChecked = it.eRunOnBoot
            content.onPluggedSwitchEnabled.isChecked = it.eRunOnPlug
        })

        viewModel.observePrioritizeBatteryIdleMode(this, Observer { content.batteryPrioritizeIdleSwitchEnabled.isChecked = it })
        viewModel.observeResetBSOnUnplug(this, Observer { content.resetStatusUnplugSwitch.isChecked = it })
        viewModel.observeResetBSOnPause(this, Observer { content.resetBSOnPauseSwitch.isChecked = it })

        viewModel.observeCapacity(this, Observer
        {
            // ACC capacity thresholds are percent (0..100) OR millivolts (3001..5000); the domain is
            // detected from pause (> 100 = mV). The percent branch is byte-identical to the original so
            // existing % configs behave exactly as before; the mV branch lets voltage-based limits be
            // seen/edited instead of being clamped to 100 and rejected. Ordering (shutdown<resume<pause)
            // is enforced by validateConfig() in both domains.
            if (it.pause > 100) {
                content.shutdownCapacityPicker.setFormatter { v -> if (v < 1) getString(R.string.disabled) else "$v mV" }
                content.shutdownCapacityPicker.minValue = 0
                content.shutdownCapacityPicker.maxValue = 5000
                content.shutdownCapacityPicker.value = it.shutdown

                content.resumeCapacityPicker.setFormatter { v -> "$v mV" }
                content.resumeCapacityPicker.minValue = 3001
                content.resumeCapacityPicker.maxValue = 5000
                content.resumeCapacityPicker.value = it.resume

                content.pauseCapacityPicker.setFormatter { v -> "$v mV" }
                content.pauseCapacityPicker.minValue = 3001
                content.pauseCapacityPicker.maxValue = 5000
                content.pauseCapacityPicker.value = it.pause
            } else {
                content.shutdownCapacityPicker.setFormatter { v -> if (v == 0) getString(R.string.disabled) else v.toString() }
                content.shutdownCapacityPicker.minValue = 0
                content.shutdownCapacityPicker.maxValue = 20
                content.shutdownCapacityPicker.value = it.shutdown

                // B18: resume floor is shutdown+1 so resume can never equal shutdown (resuming at
                // the shutdown level would race the auto power-off). Floored at 1.
                content.resumeCapacityPicker.setFormatter(null)
                content.resumeCapacityPicker.minValue = if (it.shutdown < 1) 1 else it.shutdown + 1
                content.resumeCapacityPicker.maxValue = it.pause - 1
                content.resumeCapacityPicker.value = it.resume

                content.pauseCapacityPicker.setFormatter(null)
                content.pauseCapacityPicker.minValue = it.resume + 1
                content.pauseCapacityPicker.maxValue = 100
                content.pauseCapacityPicker.value = it.pause
            }
        })

        viewModel.observeChargeSwitch(this, Observer
        {
            content.chargingSwitchTextview.text = it ?: getString(R.string.automatic)
        })

        viewModel.observeTemperature(this, Observer
        {
            content.temperatureCooldownPicker.minValue = 20
            content.temperatureCooldownPicker.maxValue = 60
            content.temperatureCooldownPicker.value = it.coolDownTemperature

            content.temperatureMaxPicker.minValue = 20
            content.temperatureMaxPicker.maxValue = 60
            content.temperatureMaxPicker.value = it.maxTemperature

            content.temperatureMaxPauseSecondsPicker.minValue = 20
            content.temperatureMaxPauseSecondsPicker.maxValue = 60
            content.temperatureMaxPauseSecondsPicker.value = it.pause

            content.temperatureShutdownPicker.minValue = 40
            content.temperatureShutdownPicker.maxValue = 70
            content.temperatureShutdownPicker.value = it.shutdown
        })

        viewModel.observeCoolDown(this, Observer
        {
            content.cooldownPercentagePicker.minValue = 0
            content.cooldownPercentagePicker.maxValue = 100
            content.cooldownPercentagePicker.value = it?.atPercent ?: 60

            content.cooldownChargeRatioPicker.minValue = 1
            content.cooldownChargeRatioPicker.maxValue = 120 //no reason behind this value
            content.cooldownChargeRatioPicker.value = it?.charge ?: 50

            content.cooldownPauseRatioPicker.minValue = 1
            content.cooldownPauseRatioPicker.maxValue = 120 //no reason behind this value
            content.cooldownPauseRatioPicker.value = it?.pause ?: 10
        })

        viewModel.observeVoltageLimit(this, Observer
        {
            content.voltageControlFileSpinner.text = it.controlFile ?: "Not supported"
            content.voltageMaxEditText.text = it.max?.let { "$it mV" } ?: getString(R.string.disabled)
        })

        viewModel.observeCurrentMax(this, Observer
        {
            content.currentMaxEditText.text = it?.let { "$it mA" } ?: getString(R.string.disabled)
        })

        viewModel.observeOnPlug(this, Observer
        { configOnPlug ->
            content.tvConfigOnPlugged.text = configOnPlug?.let { if(it.isBlank()) getString(R.string.voltage_control_file_not_set) else it } ?: getString(R.string.voltage_control_file_not_set)
        })

        viewModel.observeOnBoot(this, Observer
        { configOnBoot ->
            content.tvConfigOnBoot.text = configOnBoot?.let { if(it.isBlank()) getString(R.string.voltage_control_file_not_set) else it } ?: getString(R.string.voltage_control_file_not_set)
        })

        //--------------------------------------------------------------------------

        // InfoClick
        content.capacityControlInfo.setOnClickListener { onInfoClick(it) }
        content.powerControlInfo.setOnClickListener { onInfoClick(it) }
        content.temperatureControlInfo.setOnClickListener { onInfoClick(it) }
        content.exitOnBootInfo.setOnClickListener { onInfoClick(it) }
        content.cooldownInfo.setOnClickListener { onInfoClick(it) }
        content.onPluggedInfo.setOnClickListener { onInfoClick(it) }
        content.batteryIdleControlInfo.setOnClickListener { onInfoClick(it) }
        content.miscellaneousInfo.setOnClickListener { onInfoClick(it) }

        //capacity card
        content.shutdownCapacityPicker.setOnValueChangedListener(this)
        content.resumeCapacityPicker.setOnValueChangedListener(this)
        content.pauseCapacityPicker.setOnValueChangedListener(this)

        //temps
        content.temperatureCooldownPicker.setOnValueChangedListener(this)
        content.temperatureMaxPicker.setOnValueChangedListener(this)
        content.temperatureMaxPauseSecondsPicker.setOnValueChangedListener(this)
        content.temperatureShutdownPicker.setOnValueChangedListener(this)

        //coolDown
        content.cooldownPercentagePicker.setOnValueChangedListener(this)
        content.cooldownChargeRatioPicker.setOnValueChangedListener(this)
        content.cooldownPauseRatioPicker.setOnValueChangedListener(this)

        //power card
        if (Acc.instance.version >= 202002170) content.voltageControlFileLl.visibility = View.GONE
        else content.currentMaxLl.visibility = View.GONE

        //SwitchEnabled
        content.capacitySwitchEnabled.setOnCheckedChangeListener(this)
        content.voltcontrolSwitchEnabled.setOnCheckedChangeListener(this)
        content.batteryPrioritizeIdleSwitchEnabled.setOnCheckedChangeListener(this)
        content.tempSwitchEnabled.setOnCheckedChangeListener(this)
        content.cooldownSwitchEnabled.setOnCheckedChangeListener(this)
        content.applyOnBootSwitchEnabled.setOnCheckedChangeListener(this)
        content.onPluggedSwitchEnabled.setOnCheckedChangeListener(this)
        content.resetStatusUnplugSwitch.setOnCheckedChangeListener (this)
        content.resetBSOnPauseSwitch.setOnCheckedChangeListener(this)

        if (accConfigOnly) // FIX Checks and Visibility if loaded ONLY ACC Config
        {
            content.capacitySwitchEnabled.visibility = View.GONE
            content.tempSwitchEnabled.visibility = View.GONE

            viewModel.enables = viewModel.enables.copy(
                eCapacity = true,
                eTemperature = true,
                eVoltage = viewModel.voltageLimit.controlFile != null || viewModel.voltageLimit.max != null,
                eCurrMax = viewModel.currentMaxLimit != null,
                eCoolDown = viewModel.coolDown != null,
                eRunOnBoot = !viewModel.onBoot.isNullOrBlank(),
                eRunOnPlug = !viewModel.onPlug.isNullOrBlank()
            )
        }

        // Wire the Apply & Lock button once; the card itself stays hidden until detect()
        // confirms a verified/needs-test switch for this device.
        content.verifiedSwitchApplyButton.setOnClickListener { onApplyAndLockClick() }
        setupVerifiedSwitchCard()
    }

    /**
     * Reads the verified-switch artifact (off the main thread) and, only for a
     * [VerifiedSwitch.Verified] / [VerifiedSwitch.NeedsTest] result for THIS device,
     * shows the "Recommended charging switch" card. DeviceMismatch / NoSwitch / None
     * leave the card hidden. The actual pin always goes through a live test in
     * [onApplyAndLockClick] — this is a read-only suggestion.
     */
    private fun setupVerifiedSwitchCard()
    {
        launch {
            val result = withContext(Dispatchers.IO)
            {
                try { VerifiedSwitch.detect() }
                catch (ex: Exception)
                {
                    LogExt().e(javaClass.simpleName, "VerifiedSwitch.detect() failed: $ex")
                    VerifiedSwitch.None
                }
            }

            if (isFinishing || isDestroyed) return@launch

            verifiedSwitch = result
            when (result)
            {
                is VerifiedSwitch.Verified  -> renderDetectOrLocked(result.switch, result.klass, result.conf, result.alts)
                is VerifiedSwitch.NeedsTest -> renderDetectOrLocked(result.switch, result.klass, result.conf, result.alts)
                is VerifiedSwitch.Precondition -> renderPrecondition(result.reason)
                else -> content.verifiedSwitchCard.visibility = View.GONE
            }
        }
    }

    /** Flow-spec state selection. If the pinned + user-locked switch is one of the artifact's
     *  switches (recommended OR an alt), show the terminal LOCKED state for THAT switch -- this is
     *  what stops the "not fully verified" caveat from lingering after a successful Apply & Lock.
     *  Otherwise show the DETECTED state routed by applyMode. */
    private fun renderDetectOrLocked(spec: String, klass: String, conf: String, alts: List<VerifiedSwitch.Alt>)
    {
        val pinned = pinnedLockedNode()
        if (pinned != null)
        {
            if (spec.trim().substringBefore(' ') == pinned) { renderLocked(spec, klass, alts.size); return }
            val a = alts.firstOrNull { it.switch.trim().substringBefore(' ') == pinned }
            if (a != null) { renderLocked(a.switch, a.klass, alts.size); return }
        }
        renderSwitchState(spec, klass, conf, alts.size)
    }

    /** DETECTED state: a suggestion to apply. Caveat matches what Apply will actually DO (verified =
     *  none; pump = leak note; other live-test = a quick-test note; level-unproven = re-run). */
    private fun renderSwitchState(spec: String, klass: String, conf: String, altCount: Int)
    {
        content.verifiedSwitchTitleTv.setText(R.string.verified_switch_title)
        content.verifiedSwitchTextview.text = getString(R.string.verified_switch_for_device, spec, klass)
        content.verifiedSwitchApplyProgress.visibility = View.GONE
        when (VerifiedSwitch.applyMode(klass, conf))
        {
            VerifiedSwitch.ApplyMode.PIN_DIRECT ->
            {
                content.verifiedSwitchCaveat.visibility = View.GONE
                setApplyButton(R.string.verified_switch_apply_lock) { onApplyAndLockClick() }
            }
            VerifiedSwitch.ApplyMode.LIVE_TEST ->
            {
                content.verifiedSwitchCaveat.setText(
                    if (conf == "pump-needs-long-test") R.string.verified_switch_pump_caveat
                    else R.string.verified_switch_needs_test_caveat)
                content.verifiedSwitchCaveat.visibility = View.VISIBLE
                setApplyButton(R.string.verified_switch_apply_lock) { onApplyAndLockClick() }
            }
            VerifiedSwitch.ApplyMode.LEVEL_RERUN ->
            {
                content.verifiedSwitchCaveat.setText(R.string.verified_switch_level_rerun)
                content.verifiedSwitchCaveat.visibility = View.VISIBLE
                setApplyButton(R.string.verified_switch_rescan) { onFindSwitchClick(content.verifiedSwitchApplyButton) }
            }
        }
        content.verifiedSwitchApplyButton.visibility = View.VISIBLE
        content.verifiedSwitchApplyButton.isEnabled = true
        attachTapAll(altCount)
        content.verifiedSwitchCard.visibility = View.VISIBLE
    }

    /** Terminal LOCKED state: this switch is ACC's active, user-locked switch. No caveat, nothing
     *  to apply -- "Change switch" opens the picker. The state the card was missing. */
    private fun renderLocked(spec: String, klass: String, altCount: Int)
    {
        content.verifiedSwitchTitleTv.setText(R.string.verified_switch_title_locked)
        content.verifiedSwitchTextview.text = getString(R.string.verified_switch_locked_body, spec, klass)
        content.verifiedSwitchCaveat.visibility = View.GONE
        content.verifiedSwitchApplyProgress.visibility = View.GONE
        if (altCount > 0)
        {
            setApplyButton(R.string.verified_switch_change) { showAllSwitchesDialog() }
            content.verifiedSwitchApplyButton.visibility = View.VISIBLE
            content.verifiedSwitchApplyButton.isEnabled = true
        }
        else content.verifiedSwitchApplyButton.visibility = View.GONE
        attachTapAll(altCount)
        content.verifiedSwitchCard.visibility = View.VISIBLE
    }

    /** FAILED state: the live test did not hold, nothing was pinned. Reason + a forward action. */
    private fun renderFailed(spec: String, klass: String, altCount: Int)
    {
        content.verifiedSwitchTitleTv.setText(R.string.verified_switch_title)
        content.verifiedSwitchTextview.text = getString(R.string.verified_switch_for_device, spec, klass)
        content.verifiedSwitchApplyProgress.visibility = View.GONE
        content.verifiedSwitchCaveat.setText(R.string.verified_switch_failed_hint)
        content.verifiedSwitchCaveat.visibility = View.VISIBLE
        if (altCount > 0) setApplyButton(R.string.verified_switch_change) { showAllSwitchesDialog() }
        else setApplyButton(R.string.verified_switch_rescan) { onFindSwitchClick(content.verifiedSwitchApplyButton) }
        content.verifiedSwitchApplyButton.visibility = View.VISIBLE
        content.verifiedSwitchApplyButton.isEnabled = true
        attachTapAll(altCount)
        content.verifiedSwitchCard.visibility = View.VISIBLE
    }

    /** PRECONDITION state: the tester stopped before touching anything. Show why + a Re-scan action
     *  (a visible Apply would silently no-op since verifiedSwitchSpec() is null here). */
    private fun renderPrecondition(reason: String)
    {
        content.verifiedSwitchTitleTv.setText(R.string.verified_switch_title)
        content.verifiedSwitchTextview.text = reason.ifBlank { getString(R.string.find_switch_status_precondition) }
        content.verifiedSwitchTextview.setOnClickListener(null)
        content.verifiedSwitchCaveat.visibility = View.GONE
        content.verifiedSwitchApplyProgress.visibility = View.GONE
        setApplyButton(R.string.verified_switch_rescan) { onFindSwitchClick(content.verifiedSwitchApplyButton) }
        content.verifiedSwitchApplyButton.visibility = View.VISIBLE
        content.verifiedSwitchApplyButton.isEnabled = true
        content.verifiedSwitchCard.visibility = View.VISIBLE
    }

    private fun setApplyButton(textRes: Int, action: () -> Unit)
    {
        content.verifiedSwitchApplyButton.setText(textRes)
        content.verifiedSwitchApplyButton.setOnClickListener { action() }
    }

    /** When the tester ranked more than one working switch, make the body tappable to open the full
     *  picker; otherwise clear the listener so a stale one can't fire. */
    private fun attachTapAll(altCount: Int)
    {
        if (altCount > 0)
        {
            content.verifiedSwitchTextview.append("\n\n" + getString(R.string.verified_switch_tap_all, altCount + 1))
            content.verifiedSwitchTextview.setOnClickListener { showAllSwitchesDialog() }
        }
        else content.verifiedSwitchTextview.setOnClickListener(null)
    }

    /** The control node of the currently pinned switch IF it is user-locked (automatic OFF = " --"),
     *  else null. Compares only the first /path token so short/full forms of the same node match. */
    private fun pinnedLockedNode(): String? =
        if (viewModel.isAutomaticSwitchEanbled) null
        else viewModel.chargeSwitch?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    private fun verifiedSwitchAltCount(): Int = when (val v = verifiedSwitch)
    {
        is VerifiedSwitch.Verified -> v.alts.size
        is VerifiedSwitch.NeedsTest -> v.alts.size
        else -> 0
    }

    /**
     * The verified switch spec from the artifact, or null if the cached result is not a
     * Verified/NeedsTest hit. Format: "<path> <on> <off>".
     */
    private fun verifiedSwitchSpec(): String? = when (val v = verifiedSwitch)
    {
        is VerifiedSwitch.Verified -> v.switch
        is VerifiedSwitch.NeedsTest -> v.switch
        else -> null
    }

    /**
     * B18 helper: is the editor's currently-selected charging switch a drain type?
     *
     * The only authoritative class signal available is the verified-switch artifact's
     * `class` field, so we report drain ONLY when (a) the artifact classifies the device's
     * switch as "drain" AND (b) the selected switch's control node matches that artifact
     * switch's node. This is deliberately conservative (positive evidence only) so the
     * deep-discharge warning never false-fires on an unrelated/unknown switch.
     */
    private fun isSelectedSwitchDrainClass(): Boolean
    {
        val klass = when (val v = verifiedSwitch)
        {
            is VerifiedSwitch.Verified -> v.klass
            is VerifiedSwitch.NeedsTest -> v.klass
            else -> null
        } ?: return false
        if (!klass.equals("drain", ignoreCase = true)) return false

        val selected = viewModel.chargeSwitch ?: return false
        val verifiedNode = verifiedSwitchSpec()?.trim()?.substringBefore(' ') ?: return false
        val selectedNode = selected.trim().substringBefore(' ')
        return selectedNode.isNotEmpty() && selectedNode == verifiedNode
    }

    /**
     * Apply & Lock: live-test the recommended switch, and only on a pass write the manual
     * pin (charging_switch = "<sw> --", automatic OFF). NEVER writes without a passing test
     * — this is a charging-safety gate, enforced for Verified just as for NeedsTest. Requires
     * the phone to be charging (the live test cannot run unplugged).
     *
     * `testChargingSwitch(...) == 0` is the "works" predicate: the v202107280 handler normalises
     * a passing test to exit 0 (covering a CUT switch's exit 0, a BYPASS/IDLE switch's exit 15,
     * and a "Switch works" stdout) so a working bypass switch is no longer rejected here.
     *
     * Shows an inline spinner + live status in the card (TASK 4) instead of a silent wait.
     */
    private fun onApplyAndLockClick()
    {
        val switch = verifiedSwitchSpec() ?: return
        val v = verifiedSwitch
        val klass = when (v) { is VerifiedSwitch.Verified -> v.klass; is VerifiedSwitch.NeedsTest -> v.klass; else -> "" }
        val conf = when (v) { is VerifiedSwitch.Verified -> v.conf; is VerifiedSwitch.NeedsTest -> v.conf; else -> "" }
        applyVerifiedSpec(switch, klass, conf)
    }

    /**
     * Single pin path for ANY switch the user picks -- the recommended one OR a row from the "all
     * working switches" list. [VerifiedSwitch.applyMode] decides from class+conf: PIN_DIRECT locks
     * with no acc -t; LEVEL_RERUN asks for a lower-% re-run (acc -t can only false-fail a %-cap);
     * LIVE_TEST runs acc -t first, then pins on a pass.
     */
    private fun applyVerifiedSpec(switch: String, klass: String, conf: String)
    {
        launch {
            val mode = VerifiedSwitch.applyMode(klass, conf)
            // A1: a Verified artifact means the acc-compat tester ALREADY live-proved THIS exact switch on
            // THIS device (device+soc fingerprint matched AND it survived the long leak/re-arm test). Trust
            // it: lock directly with NO "plug in the charger" gate and NO second daemon-stopping `acca -t`
            // retest -- that double-test (the user-reported "Apply & Lock says connect charger / re-tests"
            // bug) only made sense before the tester verified holds. A cheap `[ -e node ]` recheck (instant,
            // NOT a charge test) guards a charger-path/ROM change; NeedsTest and a vanished node fall through
            // to the live-test path below unchanged.
            if (mode == VerifiedSwitch.ApplyMode.PIN_DIRECT)
            {
                // Check EVERY node of the spec, not only the first: a grouped multi-path switch with
                // vanished later paths must fall through to the live test, not pin blind.
                val nodes = switch.trim().split(' ').filter { it.startsWith("/") }
                    .ifEmpty { listOf(switch.trim().substringBefore(' ')) }
                val check = nodes.joinToString(" && ") { "[ -e \"$it\" ]" }
                val present = try { withContext(Dispatchers.IO) { Shell.su(check).exec().isSuccess } }
                catch (ex: Exception) { false }
                if (present)
                {
                    content.verifiedSwitchApplyButton.isEnabled = false
                    content.verifiedSwitchApplySpinner.visibility = View.VISIBLE
                    content.verifiedSwitchApplyProgress.visibility = View.VISIBLE
                    content.verifiedSwitchApplyStatus.text = ""
                    val ok = try { withContext(Dispatchers.IO) {
                        val w = Acc.instance.updateAccChargingSwitch(switch, false)
                        // A2: kick the daemon so the pin takes effect NOW (charging actually stops). The
                        // `--` write already made ACC set .user-locked; safe post-D2 (no un-cap on restart).
                        // Then VERIFY it came back: a failed restart would leave the pin written but
                        // unenforced until ACC's next natural cycle while the UI says Locked.
                        if (w) try
                        {
                            Shell.su(Acc.instance.getAccRestartDaemon()).exec()
                            Thread.sleep(2500)
                            val d = Shell.su("/dev/.vr25/acc/acca -D").exec().code
                            if (d != 0 && d != 8) Shell.su("/dev/.vr25/acc/acca -D restart").exec()
                        }
                        catch (_: Exception) {}
                        w
                    } }
                    catch (ex: Exception)
                    {
                        LogExt().e(javaClass.simpleName, "updateAccChargingSwitch() failed: $ex")
                        false
                    }
                    if (isFinishing || isDestroyed) return@launch
                    content.verifiedSwitchApplySpinner.visibility = View.GONE
                    if (ok)
                    {
                        viewModel.chargeSwitch = switch
                        viewModel.isAutomaticSwitchEanbled = false
                        Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
                        renderLocked(switch, klass, verifiedSwitchAltCount())   // terminal state, caveat gone
                    }
                    else
                    {
                        content.verifiedSwitchApplyStatus.setText(R.string.error_occurred)
                        content.verifiedSwitchApplyButton.isEnabled = true
                        Toast.makeText(this@AccConfigEditorActivity, R.string.error_occurred, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }

            if (mode == VerifiedSwitch.ApplyMode.LEVEL_RERUN)
            { // an unproven %-cap: acc -t writes off=pause% and, below that %, charging never stops,
              // so it can only FALSE-fail. Ask for a lower-% re-run instead of a doomed live test.
                MaterialDialog(this@AccConfigEditorActivity).show {
                    title(R.string.verified_switch_title)
                    message(R.string.verified_switch_level_rerun)
                    positiveButton(android.R.string.ok)
                }
                return@launch
            }

            val charging = try { Acc.instance.isChargerPlugged() }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "isChargerPlugged() failed: $ex")
                false
            }

            if (!charging)
            { // Cannot live-test unplugged: prompt to plug in instead of testing.
                if (isFinishing || isDestroyed) return@launch
                MaterialDialog(this@AccConfigEditorActivity).show {
                    title(R.string.verified_switch_title)
                    message(R.string.verified_switch_plug_to_apply)
                    positiveButton(android.R.string.ok)
                }
                return@launch
            }

            // Inline progress: spinner + "Testing the switch live…", button disabled meanwhile.
            content.verifiedSwitchApplyButton.isEnabled = false
            content.verifiedSwitchApplySpinner.visibility = View.VISIBLE
            content.verifiedSwitchApplyProgress.visibility = View.VISIBLE
            content.verifiedSwitchApplyStatus.setText(R.string.verified_switch_testing_live)

            val passed = try { Acc.instance.testChargingSwitch(switch) == 0 }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "testChargingSwitch() failed: $ex")
                false
            }

            if (isFinishing || isDestroyed) return@launch

            if (!passed)
            {
                content.verifiedSwitchApplySpinner.visibility = View.GONE
                Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_failed_live_test, Toast.LENGTH_LONG).show()
                renderFailed(switch, klass, verifiedSwitchAltCount())   // reason + a forward action
                return@launch
            }

            // Passed the live test: write the manual pin via the same config-update path the
            // editor uses (automatic OFF appends " --"). Done off the main thread.
            val written = try
            {
                withContext(Dispatchers.IO) {
                    val w = Acc.instance.updateAccChargingSwitch(switch, false)
                    // A2: kick the daemon so the new switch takes effect immediately (safe post-D2),
                    // then verify it came back (same guarantee as the PIN_DIRECT path).
                    if (w) try
                    {
                        Shell.su(Acc.instance.getAccRestartDaemon()).exec()
                        Thread.sleep(2500)
                        val d = Shell.su("/dev/.vr25/acc/acca -D").exec().code
                        if (d != 0 && d != 8) Shell.su("/dev/.vr25/acc/acca -D restart").exec()
                    }
                    catch (_: Exception) {}
                    w
                }
            }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "updateAccChargingSwitch() failed: $ex")
                false
            }

            if (isFinishing || isDestroyed) return@launch

            content.verifiedSwitchApplySpinner.visibility = View.GONE
            if (written)
            {
                // Reflect the pinned switch in the editor's LiveData-backed state so the shown
                // switch + the Automatic toggle refresh and a later Save keeps the pin. (These
                // two setters are the authoritative store; viewModel.profile is rebuilt from
                // them on read.)
                viewModel.chargeSwitch = switch
                viewModel.isAutomaticSwitchEanbled = false
                Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
                if (conf == "pump-needs-long-test")
                    Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_pump_note, Toast.LENGTH_LONG).show()
                renderLocked(switch, klass, verifiedSwitchAltCount())   // terminal state, caveat gone
            }
            else
            {
                content.verifiedSwitchApplyStatus.setText(R.string.error_occurred)
                content.verifiedSwitchApplyButton.isEnabled = true
                Toast.makeText(this@AccConfigEditorActivity, R.string.error_occurred, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * A3: show EVERY working switch the tester found -- the recommended one plus the alternatives --
     * each with its class (BYPASS / CUT / DRAIN / LEVEL / THROTTLE) and confidence, and which one ACC
     * is using now. Tapping a row pins THAT switch via the same [applyVerifiedSpec] path (a 'verified'
     * row locks straight away, others live-test first). The rows come from the artifact's alt* keys --
     * no tester change.
     */
    private fun showAllSwitchesDialog()
    {
        val recommended: VerifiedSwitch.Alt
        val alts: List<VerifiedSwitch.Alt>
        val accLine: String?
        when (val v = verifiedSwitch)
        {
            is VerifiedSwitch.Verified ->
            {
                recommended = VerifiedSwitch.Alt(v.switch, v.klass, v.conf, "", v.recLatch, "", v.recStability)
                alts = v.alts
                accLine = v.accCurrentSwitch?.let { "$it (${v.accCurrentClass.orEmpty()})" }
            }
            is VerifiedSwitch.NeedsTest ->
            {
                recommended = VerifiedSwitch.Alt(v.switch, v.klass, v.conf, "", v.recLatch, "", v.recStability)
                alts = v.alts
                accLine = v.accCurrentSwitch?.let { "$it (${v.accCurrentClass.orEmpty()})" }
            }
            else -> return
        }
        val all = (listOf(recommended) + alts).distinctBy { it.switch.trim() }
        val labels = all.mapIndexed { i, a ->
            val node = run {
                val toks = a.switch.trim().split(' ')
                val paths = toks.filter { it.startsWith("/") }
                if (paths.size > 1) "grouped (${paths.size} paths)"
                else {
                    val segs = (paths.firstOrNull() ?: toks.firstOrNull().orEmpty()).trim('/').split('/').filter { it.isNotEmpty() }
                    if (segs.size >= 2) segs.takeLast(2).joinToString("/") else segs.lastOrNull().orEmpty()
                }
            }
            val badge = a.klass.ifBlank { "switch" }.uppercase()
            val cf = if (a.conf == "verified") "verified" else a.conf.ifBlank { "needs test" }
            val star = if (i == 0) "  ★ recommended" else ""
            val latch = if (a.latch) " · latches" else ""
            // v6.0: surface the daemon stability so re-arming switches are SHOWN + selectable, just labeled.
            val stab = when (a.stability) {
                "daemon-held" -> " · daemon-held (re-arms, the daemon holds it)"
                "leaky"       -> " · ⚠ re-arms even when re-applied (risky)"
                "reassert"    -> " · ⚠ unstable (daemon must re-apply)"
                else          -> ""
            }
            "$badge - $node\n$cf$latch$stab$star"
        }
        // v6.0: material-dialogs renders message + listItems as EITHER/OR -- a set accLine used to suppress the
        // whole list (empty picker = the user's "AccA couldn't show/drag them in"). Move ACC's current switch into
        // the title so the full list ALWAYS renders.
        val titleTxt = getString(R.string.verified_switch_all_title) +
            (accLine?.let { "\n(now: ${it.substringAfterLast('/').substringBefore(' ')})" } ?: "")
        MaterialDialog(this@AccConfigEditorActivity).show {
            title(text = titleTxt)
            listItems(items = labels) { _, index, _ ->
                val pick = all[index]
                // Reflect the picked row in the card AND in the verifiedSwitch field, so every later
                // reader (the main Apply & Lock button via verifiedSwitchSpec(), the B18 drain-class
                // warning via isSelectedSwitchDrainClass()) sees the SAME switch the card shows --
                // updating only the TextView left them reading the old recommended pick.
                verifiedSwitch = when (val v = verifiedSwitch)
                {
                    is VerifiedSwitch.Verified -> v.copy(switch = pick.switch, klass = pick.klass, conf = pick.conf)
                    is VerifiedSwitch.NeedsTest -> v.copy(switch = pick.switch, klass = pick.klass, conf = pick.conf)
                    else -> v
                }
                content.verifiedSwitchTextview.text =
                    getString(R.string.verified_switch_for_device, pick.switch, pick.klass)
                content.verifiedSwitchCaveat.visibility =
                    if (VerifiedSwitch.applyMode(pick.klass, pick.conf) == VerifiedSwitch.ApplyMode.PIN_DIRECT) View.GONE else View.VISIBLE
                applyVerifiedSpec(pick.switch, pick.klass, pick.conf)
            }
        }
    }

    /**
     * Opens [SwitchFinderActivity], which runs the bundled acc-compat tester with a live log and,
     * on completion, lets the user apply the discovered switch. Wired from the "Find my charging
     * switch" button in the capacity card. After it returns we re-read the verified-switch
     * artifact so the editor's "Recommended charging switch" card appears if the run found one.
     */
    fun onFindSwitchClick(v: View)
    {
        startActivity(Intent(this, SwitchFinderActivity::class.java))
    }

    override fun onResume()
    {
        super.onResume()
        // Returning from the switch finder may have written a fresh artifact; re-detect so the
        // Recommended-switch card shows up without needing to re-open the editor. Only after the
        // async config load has populated viewModel (initUi already ran the first detect); the
        // re-detect is idempotent — it re-reads the artifact and shows/hides the card.
        if (::viewModel.isInitialized) setupVerifiedSwitchCard()
    }

    private fun showConfigReadError()
    {
        MaterialDialog(this).show {
            title(R.string.config_error_title)
            message(R.string.config_error_dialog)
            positiveButton(android.R.string.ok)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean
    {
        menuInflater.inflate(R.menu.acc_config_editor_menu, menu)
        mUndoMenuItem = menu.findItem(R.id.action_undo)
        // viewModel is initialized asynchronously (config read off the main thread);
        // the menu may be created before that completes. Guard against the lateinit
        // not yet being set.
        if (::viewModel.isInitialized)
            viewModel.undoOperationAvailableLiveData.observe(this, Observer { mUndoMenuItem.isEnabled = it })
        else
            mUndoMenuItem.isEnabled = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        // Ignore action items until the async config load has populated viewModel.
        if (!::viewModel.isInitialized && item.itemId != android.R.id.home)
            return super.onOptionsItemSelected(item)

        when (item.itemId)
        {
            R.id.action_save -> returnResults()
            R.id.action_restore -> viewModel.profile.accConfig = initConfig.copy()
            R.id.action_undo -> viewModel.undoLastConfigOperation()
            android.R.id.home -> { onBackPressed(); return true }
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * Blocked settings: nodes AMPS refuses to write because writing one previously took the phone
     * down mid-scan. The tester records the node before touching it and, on the next start, puts it
     * back and blocks it permanently, so a crash costs one reboot rather than a loop.
     *
     * Two lists are shown together because both are enforced: AMPS's own (.acc-compat-blacklist,
     * absolute paths) and ACC's older crash journal (.probe-blacklist, switch lines relative to the
     * power-supply dir, from the upstream #305/#308 fix).
     *
     * Removal deliberately goes through the tester's own `--blacklist rm`, not a file edit here:
     * that path is already covered by the on-device test matrix, and one implementation cannot
     * drift from the other.
     */
    private val BLOCK_DATA_DIR = "/data/adb/vr25/acc-data"       // both crash blacklists live here
    // The module's own copy, NOT /data/local/tmp: the finder only extracts the tmp one when a scan
    // actually runs, so on a phone that has never scanned it does not exist and removal silently did
    // nothing. The module copy is present whenever ACC is installed.
    private val BLOCK_TESTER = "/data/adb/vr25/acc/acc-compat.sh"

    /**
     * Wired from the "Blocked settings" button under Find my switch in the capacity card.
     * The list outgrew a dialog once it had to support add, edit, multi-select and clear, so it
     * lives in its own screen now.
     */
    fun onBlockedSettingsClick(v: View)
    {
        startActivity(Intent(this, BlockedSettingsActivity::class.java))
    }


    override fun onBackPressed()
    {
        if (::viewModel.isInitialized && viewModel.unsavedChanges)
        {
            MaterialDialog(this).show {
                    title(R.string.unsaved_changes)
                    message(R.string.unsaved_changes_message)
                    positiveButton(R.string.save) { returnResults() }
                    negativeButton(R.string.close_without_saving) { finish() }
                    neutralButton(android.R.string.cancel)
                }
        }
        else super.onBackPressed()
    }

    //-------------------------------------------------------------------------------------

    override fun onCheckedChanged(p0: CompoundButton?, p1: Boolean)
    {
        when (p0)
        {
            content.capacitySwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eCapacity = p1)
                content.shutdownCapacityPicker.isEnabled = p1
                content.resumeCapacityPicker.isEnabled = p1
                content.pauseCapacityPicker.isEnabled = p1
            }

            content.voltcontrolSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eVoltage = p1, eCurrMax = p1)
                content.editVoltageLimit.isEnabled = p1
            }

            content.batteryPrioritizeIdleSwitchEnabled ->
            {
                viewModel.prioritizeBatteryIdleMode = p1
                viewModel.profile.accConfig.prioritizeBatteryIdleMode = p1
            }

            content.tempSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eTemperature = p1)
                content.temperatureCooldownPicker.isEnabled = p1
                content.temperatureMaxPicker.isEnabled = p1
                content.temperatureMaxPauseSecondsPicker.isEnabled = p1
                content.temperatureShutdownPicker.isEnabled = p1
            }

            content.cooldownSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eCoolDown = p1)
                content.cooldownPercentagePicker.isEnabled = p1
                content.cooldownChargeRatioPicker.isEnabled = p1
                content.cooldownPauseRatioPicker.isEnabled = p1
            }

            content.applyOnBootSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eRunOnBoot = p1)
                content.tvConfigOnBoot.isEnabled = p1
                // eRunOnBoot is derived from the apply_on_boot content at load, so turning the
                // switch on with no command was a silent no-op that reverted on the next open.
                // On a real user tap, ON opens the editor (so ON always means "has a command")
                // and OFF clears it; the editor's onDismiss re-syncs the switch to the real
                // content. Programmatic sets (load / re-sync) have isPressed == false and are
                // skipped, so they never reopen the dialog or recurse.
                if (p0?.isPressed == true)
                {
                    if (p1) editOnBootOnClick(content.tvConfigOnBoot) else viewModel.onBoot = null
                }
            }

            content.onPluggedSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eRunOnPlug = p1)
                content.tvConfigOnPlugged.isEnabled = p1
                // Same content-backed toggle as apply_on_boot: on a user tap, ON opens the editor
                // and OFF clears; programmatic sets are skipped via isPressed.
                if (p0?.isPressed == true)
                {
                    if (p1) editOnPluggedOnClick(content.tvConfigOnPlugged) else viewModel.onPlug = null
                }
            }

            content.resetStatusUnplugSwitch ->
            {
                viewModel.resetBSOnUnplug = p1
                viewModel.profile.accConfig.configResetUnplugged = p1
            }

            content.resetBSOnPauseSwitch ->
            {
                viewModel.resetBSOnPause = p1
                viewModel.profile.accConfig.configResetBsOnPause = p1
            }
        }
    }

    override fun onValueChange(picker: NumberPicker?, oldVal: Int, newVal: Int)
    {
        when (picker)
        {
            //capacity
            content.shutdownCapacityPicker -> {
                viewModel.capacity = viewModel.capacity.copy(shutdown = newVal)
                if (newVal == 0 && oldVal > 0)
                {
                    // B18: when the selected switch is a drain type, shutdown=0 is a deep-discharge
                    // risk — show the stronger drain-specific warning instead of the generic one.
                    // Both are NON-BLOCKING (info only); shutdown=0 stays a valid, saveable config.
                    val drain = isSelectedSwitchDrainClass()
                    MaterialDialog(this@AccConfigEditorActivity).show {
                        title(text = getString(if (drain) R.string.drain_shutdown_zero_title else R.string.shutdown_capacity_off_title))
                        message(text = getString(if (drain) R.string.drain_shutdown_zero_warning else R.string.shutdown_capacity_off_warning))
                        positiveButton(android.R.string.ok)
                    }
                }
            }
            content.resumeCapacityPicker -> viewModel.capacity = viewModel.capacity.copy(resume = newVal)
            content.pauseCapacityPicker -> viewModel.capacity = viewModel.capacity.copy(pause = newVal)
            content.temperatureCooldownPicker -> viewModel.temperature = viewModel.temperature.copy(coolDownTemperature = newVal)
            content.temperatureMaxPicker -> viewModel.temperature = viewModel.temperature.copy(maxTemperature = newVal)
            content.temperatureMaxPauseSecondsPicker -> viewModel.temperature = viewModel.temperature.copy(pause = newVal)
            content.temperatureShutdownPicker -> viewModel.temperature = viewModel.temperature.copy(shutdown = newVal)

            //coolDown
            content.cooldownPercentagePicker, content.cooldownChargeRatioPicker,
            content.cooldownPauseRatioPicker -> viewModel.coolDown =
                AccConfig.ConfigCoolDown(
                    content.cooldownPercentagePicker.value,
                    content.cooldownChargeRatioPicker.value,
                    content.cooldownPauseRatioPicker.value)

            else -> return
        }
    }

    /**
     * Function for On Boot ImageView OnClick.
     * Opens the dialog to edit the On Boot mAccConfig parameter.
     */

    @SuppressLint("CheckResult")
    fun editOnBootOnClick(view: View)
    {
        MaterialDialog(this@AccConfigEditorActivity).show {
            title(R.string.edit_on_boot)
            // Existing description + a one-line root-warning. The hooks run as root on every
            // boot/plug, so paste-untrusted-command is the realistic foot-gun to flag here.
            message(text = getString(R.string.edit_on_boot_dialog_message) + "\n\n" + getString(R.string.on_boot_plug_root_warning))
            input(
                prefill = viewModel.onBoot ?: "",
                allowEmpty = true,
                hintRes = R.string.edit_on_boot_dialog_hint
            ) { _, text -> viewModel.onBoot = if (text.isNotBlank()) text.toString() else null }
            positiveButton(R.string.save)
            negativeButton(android.R.string.cancel)
            neutralButton(text = "clear", click = { viewModel.onBoot = null }  )
            // Re-sync the switch to the real content on any close (save / cancel / clear) so an
            // empty result flips it off at once instead of appearing to stick until the next open.
            onDismiss { content.applyOnBootSwitchEnabled.isChecked = !viewModel.onBoot.isNullOrBlank(); recomputeDirty() }
        }
    }

    @SuppressLint("CheckResult")
    fun editOnPluggedOnClick(v: View)
    {
        MaterialDialog(this@AccConfigEditorActivity).show {
            title(R.string.edit_on_plugged)
            message(text = getString(R.string.edit_on_plugged_dialog_message) + "\n\n" + getString(R.string.on_boot_plug_root_warning))
            input(
                prefill = viewModel.onPlug ?: "",
                allowEmpty = true,
                hintRes = R.string.edit_on_boot_dialog_hint
            ) { _, text -> viewModel.onPlug = if (text.trim().isNotEmpty()) text.toString() else null }
            positiveButton(R.string.save)
            negativeButton(android.R.string.cancel)
            neutralButton(text = "clear", click = { viewModel.onPlug = null }  )
            onDismiss { content.onPluggedSwitchEnabled.isChecked = !viewModel.onPlug.isNullOrBlank(); recomputeDirty() }
        }
    }

    @SuppressLint("CheckResult")
    fun editChargingSwitchOnClick(v: View)
    {
        val automaticString = getString(R.string.automatic_dialog_label)
        val addNewChargingSwitchString = getString(R.string.add_charging_switch)
        val initialSwitch = viewModel.chargeSwitch?.removeSuffix(" --")?.trim()?.ifBlank { null }

        MaterialDialog(this).show {
            title(R.string.edit_charging_switch)
            noAutoDismiss()

            launch {
                // Only surface switches we KNOW are relevant: the one currently configured, plus
                // every switch the last "Find my switch" (AMPS) run verified on THIS device. The old
                // list dumped the raw `acc -s s:` candidate tree - hundreds of untested nodes that
                // left users unable to tell which one to pick ("I cannot find my switch"). A custom
                // switch can still be added via "Add new" (live-tested + warned before it is saved).
                val knownSwitches = LinkedHashSet<String>()
                initialSwitch?.let { knownSwitches.add(it) }
                try
                {
                    // 4s cap: right after an AMPS run ACC restarts and can briefly hold the single
                    // libsu root shell, so an un-timed detect() BLOCKED here and the dialog rendered
                    // EMPTY (just Cancel) -- the "it doesn't show" field report. Time out -> fall
                    // through to the fallback below instead of hanging the dialog forever.
                    when (val vs = withTimeoutOrNull(4000) { withContext(Dispatchers.IO) { VerifiedSwitch.detect() } })
                    {
                        is VerifiedSwitch.Verified -> { knownSwitches.add(vs.switch.trim()); vs.alts.forEach { knownSwitches.add(it.switch.trim()) } }
                        is VerifiedSwitch.NeedsTest -> { knownSwitches.add(vs.switch.trim()); vs.alts.forEach { knownSwitches.add(it.switch.trim()) } }
                        else -> {}
                    }
                }
                catch (ex: Exception)
                {
                    LogExt().e(javaClass.simpleName, "VerifiedSwitch.detect() failed: $ex")
                }

                // Never open empty: if AMPS has not run yet (no verified switches beyond the current
                // one), fall back to ACC's own auto-detected switches so the picker always has real
                // options. Also timeout-capped so a busy root shell can't hang the dialog.
                if (knownSwitches.size <= 1)
                {
                    try { (withTimeoutOrNull(4000) { withContext(Dispatchers.IO) { Acc.instance.listChargingSwitches() } } ?: emptyList()).forEach { knownSwitches.add(it.trim()) } }
                    catch (ex: Exception) { LogExt().e(javaClass.simpleName, "listChargingSwitches() fallback failed: $ex") }
                }

                var chargingSwitches = listOf(
                    automaticString,
                    addNewChargingSwitchString,
                    *knownSwitches.toTypedArray()
                )

                var currentIndex = chargingSwitches.indexOf(initialSwitch ?: automaticString).let { if (it < 0) 0 else it }

                listItemsSingleChoice(
                    items = chargingSwitches,
                    initialSelection = currentIndex,
                    waitForPositiveButton = false
                ) { _, index, text ->
                    if (index == 1)
                    { //Add new charging switch
                        val previousDialog =
                            this@show //I need to keep a reference of the listItems dialog, to update the list of items
                        MaterialDialog(this@AccConfigEditorActivity).show {
                            noAutoDismiss()
                            title(text = addNewChargingSwitchString)
                            message(R.string.add_charging_switch_warning)
//                            customView(R.layout.add_charging_switch_dialog)
                            val binding = AddChargingSwitchDialogBinding.inflate(layoutInflater)
                            customView(view = binding.root)
                            positiveButton { dialog ->
//                                val view = dialog.getCustomView()
//                                val switch = "${view.charging_switch_edit_text.text} ${view.charging_switch_on_value_edit_text.text} ${view.charging_switch_off_value_edit_text.text}"
                                val switch = "${binding.chargingSwitchEditText.text} ${binding.chargingSwitchOnValueEditText.text} ${binding.chargingSwitchOffValueEditText.text}"
                                this@AccConfigEditorActivity.launch {
                                    // A new switch MUST be live-tested before it's added; running unplugged would
                                    // accept an untested entry. Mirrors the Apply&Lock plug guard. Refuse the save
                                    // here (BEFORE showing the spinner / opening the try-finally that dismisses the
                                    // Add-new dialog) so the user keeps their typed values and can plug-in + retry.
                                    val charging = try { Acc.instance.isChargerPlugged() }
                                    catch (ex: Exception)
                                    {
                                        LogExt().e(javaClass.simpleName, "isChargerPlugged() failed: $ex")
                                        false
                                    }
                                    if (!charging)
                                    {
                                        Toast.makeText(this@AccConfigEditorActivity, R.string.err_add_switch_needs_plug, Toast.LENGTH_LONG).show()
                                        return@launch
                                    }

                                    val progressDialog =
                                        MaterialDialog(this@AccConfigEditorActivity).show {
                                            title(R.string.test_switch)
                                            progress(R.string.wait)
                                        }

                                    try
                                    {
                                        var success = true

                                        if (Acc.instance.isChargerPlugged())
                                        { //If battery is charging the switch is tested
                                            if (Acc.instance.testChargingSwitch(switch) != 0)
                                            {
                                                success = false
                                                Toast.makeText(
                                                    this@AccConfigEditorActivity,
                                                    R.string.charging_switch_does_not_work,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }

                                        if (success)
                                        {
                                            chargingSwitches = listOf(*chargingSwitches.toTypedArray(), switch) //update the list of switches with the new switch

                                            if (Acc.instance.addChargingSwitch(switch))
                                            {
                                                previousDialog.updateListItemsSingleChoice(items = chargingSwitches)
                                                currentIndex = chargingSwitches.size - 1
                                            }
                                            else Toast.makeText(this@AccConfigEditorActivity, R.string.error_occurred, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    catch (ex: Exception)
                                    {
                                        ex.printStackTrace()
                                        LogExt().e(javaClass.simpleName, "add/test charging switch failed: $ex")
                                        Toast.makeText(this@AccConfigEditorActivity, R.string.error_occurred, Toast.LENGTH_SHORT).show()
                                    }
                                    finally
                                    {
                                        progressDialog.dismiss()
                                        dismiss()
                                    }
                                }
                            }
                            negativeButton { dismiss() }
                            onDismiss {
                                previousDialog.toggleItemChecked(currentIndex) //Select the correct item when closing this dialog
                            }
                        }

                        return@listItemsSingleChoice
                    }

                    currentIndex = index
                    setActionButtonEnabled(WhichButton.POSITIVE, index != -1)
                    setActionButtonEnabled(WhichButton.NEUTRAL, index != -1)
                }

                positiveButton(R.string.save) {
                    // currentIndex can be stale relative to the list (added/removed
                    // switches); getOrNull keeps it from throwing IndexOutOfBounds.
                    viewModel.chargeSwitch = if (currentIndex <= 0) null else chargingSwitches.getOrNull(currentIndex)
                    dismiss()
                }
            }

            negativeButton(android.R.string.cancel) { dismiss() }
        }
    }

    fun editPowerOnClick(v: View)
    {
        MaterialDialog(this@AccConfigEditorActivity).show {
            powerLimitDialog(viewModel.voltageLimit, viewModel.currentMaxLimit, this@AccConfigEditorActivity)
            { controlFile, voltageMaxEnabled, voltageMax, currentMaxEnabled, currentMax ->

                if (voltageMaxEnabled && voltageMax != null)
                {
                    viewModel.voltageLimit = AccConfig.ConfigVoltage(controlFile, voltageMax)
                }
                else
                {
                    viewModel.voltageLimit = viewModel.voltageLimit.copy(max = null)
                }

                viewModel.currentMaxLimit = if (currentMaxEnabled) currentMax else null

                viewModel.enables = viewModel.enables.copy(
                    eVoltage = voltageMaxEnabled && voltageMax != null,
                    eCurrMax = currentMaxEnabled && currentMax != null
                )
            }
            negativeButton(android.R.string.cancel)
        }
    }

    fun onInfoClick(v: View)
    {
        when (v)
        {
            content.capacityControlInfo -> R.string.capacity_control_info
            content.powerControlInfo -> R.string.power_control_info
            content.temperatureControlInfo -> R.string.temperature_control_info
            content.exitOnBootInfo -> R.string.description_exit_on_boot
            content.cooldownInfo -> R.string.cooldown_info
            content.onPluggedInfo -> R.string.on_plugged_info
            content.batteryIdleControlInfo -> R.string.battery_idle_info_label
            content.miscellaneousInfo -> R.string.miscellaneous_info_label
            else -> null

        }?.let {
            Tooltip.Builder(this).anchor(v, 0, 0, false).text(it).arrow(true)
                .closePolicy(ClosePolicy.TOUCH_ANYWHERE_CONSUME).showDuration(-1).overlay(false)
                .maxWidth((resources.displayMetrics.widthPixels / 1.3).toInt())
                .styleId(R.style.ToolTipAltStyle).create().show(v, Tooltip.Gravity.LEFT, true)
        }
    }

    fun onCapacityRestore(view: View)
    {
        viewModel.capacity = initConfig.configCapacity
        viewModel.chargeSwitch = initConfig.configChargeSwitch
    }

    fun onPowerControlRestore(view: View)
    {
        viewModel.voltageLimit = initConfig.configVoltage
        viewModel.currentMaxLimit = initConfig.configCurrMax
    }

    fun onTemperatureControlRestore(view: View)
    {
        viewModel.temperature = initConfig.configTemperature
    }

    fun onBootRestoreClick(view: View)
    {
        viewModel.onBoot = initConfig.configOnBoot
    }

    fun onPluggedRestore(view: View)
    {
        viewModel.onPlug = initConfig.configOnPlug
    }

    fun onCooldownRestore(view: View)
    {
        viewModel.coolDown = initConfig.configCoolDown
    }

    fun onBatteryIdleRestore(v: View)
    {
        viewModel.prioritizeBatteryIdleMode = initConfig.prioritizeBatteryIdleMode
    }

    fun onMiscRestore(v: View)
    {
        viewModel.resetBSOnUnplug = initConfig.configResetUnplugged
        viewModel.resetBSOnPause = initConfig.configResetBsOnPause
    }
}
