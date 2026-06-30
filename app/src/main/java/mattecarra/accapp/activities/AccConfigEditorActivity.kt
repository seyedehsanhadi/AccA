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

        viewModel.clearHistory()

        // On the async (no-extra) path, onCreateOptionsMenu already ran BEFORE viewModel was
        // initialised, so the undo item was disabled and its observer never wired. Rebuild the
        // menu now that viewModel exists so undo works on the edit-current-config path too.
        invalidateOptionsMenu()
    }

    private fun initUi()
    {
        viewModel.observeEnables(this, Observer
        {
            content.capacitySwitchEnabled.isChecked = it.eCapacity
            content.voltcontrolSwitchEnabled.isChecked = it.eVoltage
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
            content.capacitySwitchEnabled.visibility = View.GONE  // can't disabled
            content.tempSwitchEnabled.visibility = View.GONE

            viewModel.enables.eVoltage =
                (viewModel.voltageLimit.controlFile != null || viewModel.voltageLimit.max != null)

            viewModel.enables.eCapacity = true
            viewModel.enables.eTemperature = true
            viewModel.enables.eCoolDown = viewModel.coolDown != null
            // Derive the toggles from the actual config, like the sections above.
            // apply_on_boot / apply_on_plug are optional command hooks; ACC ships
            // them empty. Forcing the switches ON made the editor show them enabled
            // with an empty value. Reflect "is a command set" instead.
            viewModel.enables.eRunOnBoot = !viewModel.onBoot.isNullOrBlank()
            viewModel.enables.eRunOnPlug = !viewModel.onPlug.isNullOrBlank()
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
                is VerifiedSwitch.Verified ->
                {
                    content.verifiedSwitchTextview.text =
                        getString(R.string.verified_switch_for_device, result.switch, result.klass)
                    content.verifiedSwitchCaveat.visibility = View.GONE
                    content.verifiedSwitchCard.visibility = View.VISIBLE
                }
                is VerifiedSwitch.NeedsTest ->
                {
                    content.verifiedSwitchTextview.text =
                        getString(R.string.verified_switch_for_device, result.switch, result.klass)
                    content.verifiedSwitchCaveat.visibility = View.VISIBLE
                    content.verifiedSwitchCard.visibility = View.VISIBLE
                }
                else -> content.verifiedSwitchCard.visibility = View.GONE
            }

            // A3: if the tester reported other working switches too, make the card tappable to open the
            // full list (recommended + alternatives), so the user can SEE every working switch and pick
            // which one to lock -- not just the single auto-recommended one.
            val altCount = when (result)
            {
                is VerifiedSwitch.Verified -> result.alts.size
                is VerifiedSwitch.NeedsTest -> result.alts.size
                else -> 0
            }
            if (altCount > 0)
            {
                content.verifiedSwitchTextview.append("\n\n" + getString(R.string.verified_switch_tap_all, altCount + 1))
                content.verifiedSwitchTextview.setOnClickListener { showAllSwitchesDialog() }
            }
        }
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
        applyVerifiedSpec(switch, verifiedSwitch is VerifiedSwitch.Verified)
    }

    /**
     * Single pin path for ANY switch the user picks -- the recommended one OR a row from the
     * "all working switches" list. trustVerified == the picked spec's own conf was "verified", so we
     * lock it directly (no charger gate, no `acca -t` retest, A1); otherwise we live-test first.
     */
    private fun applyVerifiedSpec(switch: String, trustVerified: Boolean)
    {
        launch {
            // A1: a Verified artifact means the acc-compat tester ALREADY live-proved THIS exact switch on
            // THIS device (device+soc fingerprint matched AND it survived the long leak/re-arm test). Trust
            // it: lock directly with NO "plug in the charger" gate and NO second daemon-stopping `acca -t`
            // retest -- that double-test (the user-reported "Apply & Lock says connect charger / re-tests"
            // bug) only made sense before the tester verified holds. A cheap `[ -e node ]` recheck (instant,
            // NOT a charge test) guards a charger-path/ROM change; NeedsTest and a vanished node fall through
            // to the live-test path below unchanged.
            if (trustVerified)
            {
                val node = switch.trim().substringBefore(' ')
                val present = try { withContext(Dispatchers.IO) { Shell.su("[ -e \"$node\" ]").exec().isSuccess } }
                catch (ex: Exception) { false }
                if (present)
                {
                    content.verifiedSwitchApplyButton.isEnabled = false
                    content.verifiedSwitchApplySpinner.visibility = View.VISIBLE
                    content.verifiedSwitchApplyProgress.visibility = View.VISIBLE
                    content.verifiedSwitchApplyStatus.setText(R.string.verified_switch_locked)
                    val ok = try { withContext(Dispatchers.IO) {
                        val w = Acc.instance.updateAccChargingSwitch(switch, false)
                        // A2: kick the daemon so the pin takes effect NOW (charging actually stops). The
                        // `--` write already made ACC set .user-locked; safe post-D2 (no un-cap on restart).
                        if (w) try { Shell.su(Acc.instance.getAccRestartDaemon()).exec() } catch (_: Exception) {}
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
                        content.verifiedSwitchApplyStatus.setText(R.string.verified_switch_locked)
                        Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
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
                content.verifiedSwitchApplyStatus.setText(R.string.verified_switch_failed_live_test)
                content.verifiedSwitchApplyButton.isEnabled = true
                Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_failed_live_test, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Passed the live test: write the manual pin via the same config-update path the
            // editor uses (automatic OFF appends " --"). Done off the main thread.
            val written = try
            {
                withContext(Dispatchers.IO) {
                    val w = Acc.instance.updateAccChargingSwitch(switch, false)
                    // A2: kick the daemon so the new switch takes effect immediately (safe post-D2).
                    if (w) try { Shell.su(Acc.instance.getAccRestartDaemon()).exec() } catch (_: Exception) {}
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
                content.verifiedSwitchApplyStatus.setText(R.string.verified_switch_locked)
                Toast.makeText(this@AccConfigEditorActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
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
            "$badge — $node\n$cf$latch$stab$star"
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
                applyVerifiedSpec(pick.switch, pick.conf == "verified")
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

    fun onBatteryIdleTestButtonClick(v: View)
    {
        launch {
            val dialog = MaterialDialog(this@AccConfigEditorActivity).show {
                title(R.string.test_battery_idle)
                progress(R.string.wait)
            }

            val exitCode: Int
            val supported: Boolean
            try
            {
                val result = Acc.instance.isBatteryIdleSupported()
                exitCode = result.first
                supported = result.second
            }
            catch (ex: Exception)
            {
                ex.printStackTrace()
                LogExt().e(javaClass.simpleName, "isBatteryIdleSupported() failed: $ex")
                if (dialog.isShowing) dialog.cancel()
                return@launch
            }

            if (dialog.isShowing)
            {
                dialog.cancel()

                if (exitCode == 2)
                { //battery is not charging -> can not test
                    MaterialDialog(this@AccConfigEditorActivity).show {
                        title(R.string.test_battery_idle)
                        message(R.string.plug_battery_to_test)
                        positiveButton(R.string.retry) {
                            onBatteryIdleTestButtonClick(v)
                        }
                        negativeButton(android.R.string.cancel)
                    }
                }
                else
                {
                    if (!supported) viewModel.prioritizeBatteryIdleMode = false
                    content.batteryPrioritizeIdleSwitchEnabled.isEnabled = supported

                    MaterialDialog(this@AccConfigEditorActivity).show {
                        title(R.string.test_battery_idle)

                        if (!supported)
                        {
                            content.batteryPrioritizeIdleSwitchEnabled.isChecked = false
                            message(R.string.test_battery_idle_unsupported_result)
                        }
                        else
                        {
                            message(R.string.test_battery_idle_supported_result)
                        }
                        positiveButton(android.R.string.ok)
                    }
                }

            }
        }
    }

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
                viewModel.enables = viewModel.enables.copy(eVoltage = p1)
                content.editVoltageLimit.isEnabled = p1
            }

            content.batteryPrioritizeIdleSwitchEnabled ->
            {
                viewModel.prioritizeBatteryIdleMode = p1
                viewModel.profile.accConfig.prioritizeBatteryIdleMode = p1
                content.batteryIdleTestButton.isEnabled = p1
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
            }

            content.onPluggedSwitchEnabled ->
            {
                viewModel.enables = viewModel.enables.copy(eRunOnPlug = p1)
                content.tvConfigOnPlugged.isEnabled = p1
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

        }
    }

    @SuppressLint("CheckResult")
    fun editChargingSwitchOnClick(v: View)
    {
        val automaticString = getString(R.string.automatic)
        val addNewChargingSwitchString = getString(R.string.add_charging_switch)
        val initialSwitch = viewModel.chargeSwitch

        MaterialDialog(this).show {
            title(R.string.edit_charging_switch)
            noAutoDismiss()

            launch {
                val existingSwitches = try
                {
                    Acc.instance.listChargingSwitches().toTypedArray()
                }
                catch (ex: Exception)
                {
                    ex.printStackTrace()
                    LogExt().e(javaClass.simpleName, "listChargingSwitches() failed: $ex")
                    if (isShowing) dismiss()
                    return@launch
                }

                var chargingSwitches = listOf(
                    automaticString,
                    addNewChargingSwitchString,
                    *existingSwitches
                )

                var currentIndex = chargingSwitches.indexOf(initialSwitch ?: automaticString)

                setActionButtonEnabled(WhichButton.POSITIVE, currentIndex != -1)
                setActionButtonEnabled(WhichButton.NEUTRAL, currentIndex != -1)

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

                neutralButton(R.string.test_switch) {
                    val switch = if (currentIndex <= 0) null else chargingSwitches.getOrNull(currentIndex)

                    val dialog = MaterialDialog(this@AccConfigEditorActivity).show {
                        title(R.string.test_switch)
                        progress(R.string.wait)
                    }

                    this@AccConfigEditorActivity.launch {
                        val description = try
                        {
                            when (Acc.instance.testChargingSwitch(switch))
                            {
                                0 -> R.string.charging_switch_works
                                1 -> R.string.charging_switch_does_not_work
                                2 -> R.string.plug_battery_to_test
                                else -> R.string.error_occurred
                            }
                        }
                        catch (ex: Exception)
                        {
                            ex.printStackTrace()
                            LogExt().e(javaClass.simpleName, "testChargingSwitch() failed: $ex")
                            R.string.error_occurred
                        }

                        if (dialog.isShowing) dialog.cancel()

                        MaterialDialog(this@AccConfigEditorActivity).show {
                            title(R.string.test_switch)
                            message(description)
                            positiveButton(android.R.string.ok)
                        }
                    }
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
    }
}
