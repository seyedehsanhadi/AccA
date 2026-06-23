package mattecarra.accapp.acc

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mattecarra.accapp.acc._interface.AccInterface
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.utils.LogExt

const val TAG = "ConfigUpdater()"

//----------------------------------------------------------------------
// Class to enable\disable Shell commands for greater flexibility

data class ConfigUpdaterEnable(  // primary constructor, all values as TRUE
    var sendCapacity: Boolean = true,
    var sendVoltage: Boolean = true,
    var sendCurrMax: Boolean = true,
    var sendTemperature: Boolean = true,
    var sendCoolDown: Boolean = true,
    var sendOnBoot: Boolean = true,
    var sendOnPlug: Boolean = true,
    var sendResetUnplugged: Boolean = true,
    var sendResetBsOnPause: Boolean = true,
    var sendChargeSwitch: Boolean = true,
    var sendBatteryIdleMode: Boolean = true
) {

    // Secondary constructor, values are obtained from setting over the base
    constructor(mSharedPrefs: SharedPreferences) : this()
    {
        getParam(mSharedPrefs)
    }

    fun getParam(mSharedPrefs: SharedPreferences)
    {
        sendCurrMax = mSharedPrefs.getBoolean("cueCurrMax", true)
        sendVoltage = mSharedPrefs.getBoolean("cueVoltage", true)
    }
}

//----------------------------------------------------------------------

data class ConfigUpdater(val accConfig: AccConfig, val cue: ConfigUpdaterEnable)
{
    suspend fun execute(acc: AccInterface): ConfigUpdateResult = withContext(Dispatchers.IO) {

        LogExt().d(TAG, "pEnable: $cue")
        LogExt().d(TAG, "pAcc: $accConfig")

        val capacityUpdate = cue.sendCapacity && acc.updateAccCapacity(accConfig.configCapacity.shutdown, accConfig.configCoolDown?.atPercent ?: 101, accConfig.configCapacity.resume, accConfig.configCapacity.pause)
        val voltControl = cue.sendVoltage && acc.updateAccVoltControl(accConfig.configVoltage.controlFile, accConfig.configVoltage.max)
        // Some handlers (legacy, v201910132) return "" for the current-max command (NOT SUPPORTED).
        // An empty su command exits 0 -> would falsely report success. Treat an unsupported/empty
        // command as "not applicable" (do not run it, do not mark it STATUS_OK).
        val currentMaxSupported = acc.getUpdateAccCurrentMaxCommand(accConfig.configCurrMax).isNotBlank()
        val currentMax = cue.sendCurrMax && currentMaxSupported && acc.updateAccCurrentMaxCommand(accConfig.configCurrMax)
        val temper = cue.sendTemperature && acc.updateAccTemperature(accConfig.configTemperature.coolDownTemperature, accConfig.configTemperature.maxTemperature, accConfig.configTemperature.pause, accConfig.configTemperature.shutdown)
        val coolDown = cue.sendCoolDown && acc.updateAccCoolDown(accConfig.configCoolDown?.charge, accConfig.configCoolDown?.pause )
        val resetUnplugged = cue.sendResetUnplugged && acc.updateResetUnplugged(accConfig.configResetUnplugged)
        val resetBSOnPause = cue.sendResetBsOnPause && acc.updateResetOnPause(accConfig.configResetBsOnPause)
        val onBootUpdateSuccessful = cue.sendOnBoot && acc.updateAccOnBoot(accConfig.configOnBoot)
        val onPlugged = cue.sendOnPlug && acc.updateAccOnPlugged(accConfig.configOnPlug)
        val chargingSwitch = cue.sendChargeSwitch && acc.updateAccChargingSwitch(accConfig.configChargeSwitch, accConfig.configIsAutomaticSwitchingEnabled)
        val prioritizeBatteryIdleMode = cue.sendBatteryIdleMode && acc.updatePrioritizeBatteryIdleMode(accConfig.prioritizeBatteryIdleMode)

        LogExt().d(TAG, (if(!cue.sendCapacity) "[off]" else if (capacityUpdate) "[ok]" else "[fail]")+" capacity: ${accConfig.configCapacity}")
        LogExt().d(TAG, (if(!cue.sendVoltage) "[off]" else if (voltControl) "[ok]" else "[fail]")+" voltage: ${accConfig.configVoltage}")
        LogExt().d(TAG, (if(!cue.sendCurrMax) "[off]" else if (currentMax) "[ok]" else "[fail]")+" currentMax: ${accConfig.configCurrMax}")
        LogExt().d(TAG, (if(!cue.sendTemperature) "[off]" else if (temper) "[ok]" else "[fail]")+" temperature: ${accConfig.configTemperature}")
        LogExt().d(TAG, (if(!cue.sendCoolDown) "[off]" else if (coolDown) "[ok]" else "[fail]")+" coolDown: ${accConfig.configCoolDown}")
        LogExt().d(TAG, (if(!cue.sendResetUnplugged) "[off]" else if (resetUnplugged) "[ok]" else "[fail]")+" resetUnplugged: ${accConfig.configResetUnplugged}")
        LogExt().d(TAG, (if(!cue.sendResetBsOnPause) "[off]" else if (resetBSOnPause) "[ok]" else "[fail]")+" resetBSOnPause: ${accConfig.configResetBsOnPause}")
        LogExt().d(TAG, (if(!cue.sendOnBoot) "[off]" else if (onBootUpdateSuccessful) "[ok]" else "[fail]")+" onBoot: ${accConfig.configOnBoot}")
        LogExt().d(TAG, (if(!cue.sendOnPlug) "[off]" else if (onPlugged) "[ok]" else "[fail]")+" onPlugged: ${accConfig.configOnPlug}")
        LogExt().d(TAG, (if(!cue.sendChargeSwitch) "[off]" else if (chargingSwitch) "[ok]" else "[fail]")+" chargingSwitch: ${accConfig.configChargeSwitch}")
        LogExt().d(TAG, (if(!cue.sendBatteryIdleMode) "[off]" else if (prioritizeBatteryIdleMode) "[ok]" else "[fail]")+" batteryIdleMode: ${accConfig.prioritizeBatteryIdleMode}")

        // Per-key failures are otherwise only at debug level (.d), suppressed unless debug is on
        // (mDEBUG defaults to NONE). Log any failure at the always-on 'S' level so a partial apply
        // always leaves a trace in logcat regardless of the debug setting.
        if (cue.sendCapacity && !capacityUpdate) LogExt().s(TAG, "[fail] capacity: ${accConfig.configCapacity}")
        if (cue.sendVoltage && !voltControl) LogExt().s(TAG, "[fail] voltage: ${accConfig.configVoltage}")
        if (cue.sendCurrMax && currentMaxSupported && !currentMax) LogExt().s(TAG, "[fail] currentMax: ${accConfig.configCurrMax}")
        if (cue.sendTemperature && !temper) LogExt().s(TAG, "[fail] temperature: ${accConfig.configTemperature}")
        if (cue.sendCoolDown && !coolDown) LogExt().s(TAG, "[fail] coolDown: ${accConfig.configCoolDown}")
        if (cue.sendResetUnplugged && !resetUnplugged) LogExt().s(TAG, "[fail] resetUnplugged: ${accConfig.configResetUnplugged}")
        if (cue.sendResetBsOnPause && !resetBSOnPause) LogExt().s(TAG, "[fail] resetBSOnPause: ${accConfig.configResetBsOnPause}")
        if (cue.sendOnBoot && !onBootUpdateSuccessful) LogExt().s(TAG, "[fail] onBoot: ${accConfig.configOnBoot}")
        if (cue.sendOnPlug && !onPlugged) LogExt().s(TAG, "[fail] onPlugged: ${accConfig.configOnPlug}")
        if (cue.sendChargeSwitch && !chargingSwitch) LogExt().s(TAG, "[fail] chargingSwitch: ${accConfig.configChargeSwitch}")
        if (cue.sendBatteryIdleMode && !prioritizeBatteryIdleMode) LogExt().s(TAG, "[fail] batteryIdleMode: ${accConfig.prioritizeBatteryIdleMode}")

        val temp = ConfigUpdateResult(
            capacityUpdateSuccessful = if (cue.sendCapacity) (if (capacityUpdate) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            voltControlUpdateSuccessful = if (cue.sendVoltage) (if (voltControl) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            currentMaxUpdateSuccessful = if (cue.sendCurrMax && currentMaxSupported) (if (currentMax) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            tempUpdateSuccessful = if (cue.sendTemperature) (if (temper) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            coolDownUpdateSuccessful = if (cue.sendCoolDown) (if (coolDown) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            resetUnpluggedUpdateSuccessful = if (cue.sendResetUnplugged) (if (resetUnplugged) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            resetBSOnPauseSuccessful = if (cue.sendResetBsOnPause) (if (resetBSOnPause) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            onBootUpdateSuccessful = if (cue.sendOnBoot) (if (onBootUpdateSuccessful) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            onPluggedUpdateSuccessful = if (cue.sendOnPlug) (if (onPlugged) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            chargingSwitchUpdateSuccessful = if (cue.sendChargeSwitch) (if (chargingSwitch) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
            prioritizeBatteryIdleModeSuccessful = if (cue.sendBatteryIdleMode) (if (prioritizeBatteryIdleMode) ConfigUpdateStatus.STATUS_OK else ConfigUpdateStatus.STATUS_FAIL ) else ConfigUpdateStatus.STATUS_OFF,
        )

        LogExt().d(TAG, "ConfigUpdateResult()=${temp.isSuccessful()}")

        // NOTE: do NOT restart the daemon here. A restart re-detects every charging
        // switch (several seconds) and froze the app on every settings change (1.0.47).
        // ACC live-reads the limit/temperature within a few seconds on its own; only a
        // charging-switch change needs a restart, handled from the dashboard button.

        temp
    }

    fun concatenateCommands(acc: AccInterface): String
    {
        return arrayOf(
            acc.getUpdateAccCapacityCommand(
                accConfig.configCapacity.shutdown,
                accConfig.configCoolDown?.atPercent ?: 101,
                accConfig.configCapacity.resume,
                accConfig.configCapacity.pause ),
            acc.getUpdateAccVoltControlCommand(
                accConfig.configVoltage.controlFile,
                accConfig.configVoltage.max ),
            acc.getUpdateAccCurrentMaxCommand(accConfig.configCurrMax),
            acc.getUpdateAccTemperatureCommand(
                accConfig.configTemperature.coolDownTemperature,
                accConfig.configTemperature.maxTemperature,
                accConfig.configTemperature.pause,
                accConfig.configTemperature.shutdown ),
            acc.getUpdateAccCoolDownCommand(
                accConfig.configCoolDown?.charge,
                accConfig.configCoolDown?.pause ),
            acc.getUpdateResetUnpluggedCommand(accConfig.configResetUnplugged),
            acc.getUpdateResetOnPauseCommand(accConfig.configResetBsOnPause),
            acc.getUpdateAccOnBootCommand(accConfig.configOnBoot),
            acc.getUpdateAccOnPluggedCommand(accConfig.configOnPlug),
            acc.getUpdateAccChargingSwitchCommand(accConfig.configChargeSwitch, accConfig.configIsAutomaticSwitchingEnabled),
            acc.getUpdatePrioritizeBatteryIdleModeCommand(accConfig.prioritizeBatteryIdleMode)
        ).joinToString("; ")
    }
}

/**
 * Enum class for interpreting update results
 */
enum class ConfigUpdateStatus { STATUS_OFF, STATUS_OK, STATUS_FAIL }

/**
 * Data class for returning and interpreting update results when applying new values.
 */
data class ConfigUpdateResult(
    val capacityUpdateSuccessful: ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val voltControlUpdateSuccessful: ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val currentMaxUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val tempUpdateSuccessful: ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val coolDownUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val resetUnpluggedUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val resetBSOnPauseSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val onBootUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val onPluggedUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val chargingSwitchUpdateSuccessful:  ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF,
    val prioritizeBatteryIdleModeSuccessful: ConfigUpdateStatus = ConfigUpdateStatus.STATUS_OFF
) {

    fun isSuccessful(): Boolean
    {
        // Check ALL 11 results. Previously 3 were omitted (currentMax, resetBSOnPause,
        // prioritizeBatteryIdleMode), so a failed current-cap / reset-on-pause / battery-idle
        // write was silently reported to the user as "saved".
        return capacityUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && voltControlUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && currentMaxUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && tempUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && coolDownUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && resetUnpluggedUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && resetBSOnPauseSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && onBootUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && onPluggedUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && chargingSwitchUpdateSuccessful != ConfigUpdateStatus.STATUS_FAIL
                && prioritizeBatteryIdleModeSuccessful != ConfigUpdateStatus.STATUS_FAIL
    }
}
