package mattecarra.accapp

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import mattecarra.accapp.djs.Djs
import mattecarra.accapp.utils.Constants.ACCD_USER_STOPPED
import mattecarra.accapp.utils.Constants.ACC_VERSION
import mattecarra.accapp.utils.Constants.AUTO_REKICK_ON_PLUG
import mattecarra.accapp.utils.Constants.UPDATE_NOTIFICATIONS
import mattecarra.accapp.utils.Constants.UPDATE_DISMISSED_ACCA
import mattecarra.accapp.utils.Constants.UPDATE_DISMISSED_ACC
import mattecarra.accapp.utils.Constants.CHARGE_METER_BATTERY_SOURCE
import mattecarra.accapp.utils.Constants.CHARGE_METER_DISPLAY
import mattecarra.accapp.utils.Constants.CHARGE_METER_ENABLED
import mattecarra.accapp.utils.Constants.CHARGE_METER_SHOW_TEMP
import mattecarra.accapp.utils.Constants.CHARGE_METER_STYLE
import mattecarra.accapp.utils.Constants.CURRENT_INPUT_UNIT_OF_MEASURE
import mattecarra.accapp.utils.Constants.CURRENT_OUTPUT_UNIT_OF_MEASURE
import mattecarra.accapp.utils.Constants.DJS_ENABLED
import mattecarra.accapp.utils.Constants.INCLUDE_PRE_RELEASES
import mattecarra.accapp.utils.Constants.TEMPERATURE_OUTPUT_UNIT_OF_MEASURE
import mattecarra.accapp.utils.Constants.THEME
import mattecarra.accapp.utils.Constants.VOLTAGE_INPUT_UNIT_OF_MEASURE
import mattecarra.accapp.utils.Constants.VOLTAGE_OUTPUT_UNIT_OF_MEASURE

enum class CurrentUnit { uA, mA, A }
enum class VoltageUnit { uV, mV, V }
enum class TemperatureUnit { CF, C, F }
enum class MeasureModeUnit { raw, input, output }

class Preferences(private val context: Context)
{
    private val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var currentInputUnitOfMeasure: CurrentUnit
        get() = sharedPrefs.getString(CURRENT_INPUT_UNIT_OF_MEASURE, null)?.let {
            when(it) {
                "uA" -> CurrentUnit.uA
                "mA" -> CurrentUnit.mA
                else -> CurrentUnit.A
            }
        } ?: CurrentUnit.A
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putString(CURRENT_INPUT_UNIT_OF_MEASURE, when(value) {
                    CurrentUnit.uA -> "uA"
                    CurrentUnit.mA -> "mA"
                    CurrentUnit.A ->  "A"
                }
            )
            editor.apply()
        }

    var voltageInputUnitOfMeasure: VoltageUnit
        get() = sharedPrefs.getString(VOLTAGE_INPUT_UNIT_OF_MEASURE, null)?.let {
            when(it) {
                "uV" -> VoltageUnit.uV
                "mV" -> VoltageUnit.mV
                else -> VoltageUnit.V
            }
        } ?: VoltageUnit.V
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putString(VOLTAGE_INPUT_UNIT_OF_MEASURE, when(value) {
                VoltageUnit.uV -> "uV"
                VoltageUnit.mV -> "mV"
                VoltageUnit.V -> "V"
            })
            editor.apply()
        }

    //------------------------------------------------------------------------------

    var currentOutputUnitOfMeasure: CurrentUnit
        get() = sharedPrefs.getString(CURRENT_OUTPUT_UNIT_OF_MEASURE, null)?.let {
            when(it) {
                "uA" -> CurrentUnit.uA
                "mA" -> CurrentUnit.mA
                else -> CurrentUnit.A
            }
        } ?: CurrentUnit.mA
        set(value) {
            sharedPrefs.edit().putString(CURRENT_OUTPUT_UNIT_OF_MEASURE, when(value) {
                CurrentUnit.uA -> "uA"
                CurrentUnit.mA -> "mA"
                CurrentUnit.A ->  "A" }).apply()
        }

    var voltageOutputUnitOfMeasure: VoltageUnit
        get() = sharedPrefs.getString(VOLTAGE_OUTPUT_UNIT_OF_MEASURE, null)?.let {
            when(it) {
                "uV" -> VoltageUnit.uV
                "mV" -> VoltageUnit.mV
                else -> VoltageUnit.V
            }
        } ?: VoltageUnit.mV
        set(value) {
            sharedPrefs.edit().putString(VOLTAGE_OUTPUT_UNIT_OF_MEASURE, when(value) {
                VoltageUnit.uV -> "uV"
                VoltageUnit.mV -> "mV"
                VoltageUnit.V -> "V" }).apply()
        }

    var temperatureOutputUnitOfMeasure: TemperatureUnit
        get() = sharedPrefs.getString(TEMPERATURE_OUTPUT_UNIT_OF_MEASURE, null)?.let {
            when(it) {
                "C" -> TemperatureUnit.C
                "F" -> TemperatureUnit.F
                else -> TemperatureUnit.CF
            }
        } ?: TemperatureUnit.CF
        set(value) {
            sharedPrefs.edit().putString(TEMPERATURE_OUTPUT_UNIT_OF_MEASURE, when(value) {
                TemperatureUnit.C -> "C"
                TemperatureUnit.F -> "F"
                TemperatureUnit.CF -> "CF" }).apply()
        }

    //------------------------------------------------------------------------------

    var accVersion: String
        get() = sharedPrefs.getString(ACC_VERSION, "bundled") ?: "bundled"
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putString(ACC_VERSION, value)
            editor.apply()
        }

    var appTheme: String?
        get() = sharedPrefs.getString(THEME, "2")
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putString(THEME, value ?: "2")
            editor.apply()
        }

    var lastUpdateCheck: Long
        get() = sharedPrefs.getLong("LAST_UPDATE_CHECK", -1)
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putLong("LAST_UPDATE_CHECK", value)
            editor.apply()
        }

    var lastCommit: String?
        get() = sharedPrefs.getString("LAST_COMMIT", null)
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putString("LAST_COMMIT", value)
            editor.apply()
        }

    var includePreReleases: Boolean
        get() = sharedPrefs.getBoolean(INCLUDE_PRE_RELEASES, true)
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putBoolean(INCLUDE_PRE_RELEASES, value)
            editor.apply()
        }

    // Djs.isDjsInstalled() does a blocking root Shell.su; this getter ran on the main thread
    // (SettingsFragment, AccBootReceiver) on EVERY read -> ANR. Cache the install status (it
    // can't change without a reinstall) so only the first read can ever block.
    // IMPORTANT: only cache a CONFIRMED true. If the probe throws (e.g. root not yet granted
    // right after enabling DJS) we must NOT cache false, otherwise the whole process is stuck
    // reporting "not installed" until restart. A failed probe leaves the cache null so a later
    // successful probe can populate it.
    var djsEnabled: Boolean
        get() = sharedPrefs.getBoolean(DJS_ENABLED, false) && run {
            djsInstalledCache ?: run {
                val installed = try { Djs.isDjsInstalled(context.filesDir) } catch (e: Exception) { null }
                if (installed == true) djsInstalledCache = true
                installed ?: false
            }
        }
        set(value) {
            val editor = sharedPrefs.edit()
            editor.putBoolean(DJS_ENABLED, value)
            editor.apply()
        }

    // Status-bar charge meter (rc15). Read-only display; off by default.
    var chargeMeterEnabled: Boolean
        get() = sharedPrefs.getBoolean(CHARGE_METER_ENABLED, false)
        set(value) { sharedPrefs.edit().putBoolean(CHARGE_METER_ENABLED, value).apply() }

    // "auto" | "w" | "ma" - what the status-bar number shows (auto = watts, or mA when tiny).
    var chargeMeterDisplay: String
        get() = sharedPrefs.getString(CHARGE_METER_DISPLAY, "auto") ?: "auto"
        set(value) { sharedPrefs.edit().putString(CHARGE_METER_DISPLAY, value).apply() }

    // "notif" = detailed dashboard notification with a plain static icon; "both" (default) = the
    // live number in the strip + the detailed notification in the shade. The old "icon" (number
    // with no shade card) is migrated to "both": Android cannot show a notification icon without
    // its shade row, and MIUI suppresses the icon too when the row is emptied (device-verified).
    var chargeMeterStyle: String
        get() = (sharedPrefs.getString(CHARGE_METER_STYLE, "both") ?: "both").let { if (it == "icon") "both" else it }
        set(value) { sharedPrefs.edit().putString(CHARGE_METER_STYLE, value).apply() }

    // "system" (default) = the OS battery level: matches the status bar and the original AccA, no
    // root needed, and CARRIES the Capacity Mask when one is set (the mask works by writing
    // Android's battery state, so the OS reading is the masked one).
    // "acc" = ACC's capacityPct, read from the kernel percent, so it is the TRUE level and
    // IGNORES the mask. Device-checked with a mask active: system showed 86, acc showed 76.
    // Default is "system" so the app agrees with the status bar. Note this is a DISPLAY choice
    // only: ACC's charging decisions always use the kernel value, never a number it wrote itself.
    var chargeMeterBatterySource: String
        get() = sharedPrefs.getString(CHARGE_METER_BATTERY_SOURCE, "system") ?: "system"
        set(value) { sharedPrefs.edit().putString(CHARGE_METER_BATTERY_SOURCE, value).apply() }

    // Off by default. Adds battery temperature next to the strip number, in whatever unit
    // temperatureOutputUnitOfMeasure (Settings > Units of measure) is already set to - no
    // separate C/F choice for the meter, so the one global unit picker stays the single source
    // of truth and can't disagree with itself between the dashboard and the status bar.
    var chargeMeterShowTemp: Boolean
        get() = sharedPrefs.getBoolean(CHARGE_METER_SHOW_TEMP, false)
        set(value) { sharedPrefs.edit().putBoolean(CHARGE_METER_SHOW_TEMP, value).apply() }

    // True only when the user DELIBERATELY stopped accd (dashboard toggle, QS tile, widget dialog).
    // The plug-in daemon guard checks this so it never resurrects a daemon the user turned off;
    // any manual start/restart clears it.
    var accdUserStopped: Boolean
        get() = sharedPrefs.getBoolean(ACCD_USER_STOPPED, false)
        set(value) { sharedPrefs.edit().putBoolean(ACCD_USER_STOPPED, value).apply() }

    var autoRekickOnPlug: Boolean
        get() = sharedPrefs.getBoolean(AUTO_REKICK_ON_PLUG, false)
        set(value) { sharedPrefs.edit().putBoolean(AUTO_REKICK_ON_PLUG, value).apply() }

    var updateNotifications: Boolean
        get() = sharedPrefs.getBoolean(UPDATE_NOTIFICATIONS, true)
        set(value) { sharedPrefs.edit().putBoolean(UPDATE_NOTIFICATIONS, value).apply() }

    var dismissedAccaVersion: String
        get() = sharedPrefs.getString(UPDATE_DISMISSED_ACCA, "") ?: ""
        set(value) { sharedPrefs.edit().putString(UPDATE_DISMISSED_ACCA, value).apply() }

    var dismissedAccVersionCode: Int
        get() = sharedPrefs.getInt(UPDATE_DISMISSED_ACC, 0)
        set(value) { sharedPrefs.edit().putInt(UPDATE_DISMISSED_ACC, value).apply() }

    companion object { @Volatile private var djsInstalledCache: Boolean? = null }
}