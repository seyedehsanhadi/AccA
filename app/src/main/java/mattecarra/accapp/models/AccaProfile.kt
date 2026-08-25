package mattecarra.accapp.models

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

    @Entity(tableName = "profiles_table")
    data class AccaProfile(

        @PrimaryKey(autoGenerate = true) val uid: Int,
        var profileName: String,
        @Embedded var accConfig: AccConfig,
        var pEnables: ProfileEnables,
        var pScripts: List<Int>? = null, // contain uid from script_table
    ) : Serializable {

    /**
     * The config to actually push to ACC, with the profile's own enable toggles honoured.
     *
     * `pEnables.eCapacity` and `configCapacity` are stored in separate columns and could disagree:
     * turning capacity control off wrote only the enable flag, so the numbers stayed at e.g.
     * pause=75 and applying the profile still sent a 75% limit. The profile screen hid the row
     * (it reads pEnables) while the dashboard showed a live limit (it reads back what ACC got),
     * which is exactly the disparity users reported.
     *
     * Newer saves write pause=100 at toggle time, but profiles saved before that still carry the
     * old numbers, so reconcile here as well: with capacity control off the applied config always
     * carries pause=100, ACC's "charge to full", i.e. no capacity limit -- temperature, voltage and
     * current stay in force. 100 and not 101: ACC validates pause as 1..100 and clamps 101 to 80,
     * measured on a Pixel 6a.
     */
    fun configForApply(): AccConfig {
        // "with the profile's own enable toggles honoured" -- plural, but only eCapacity was ever
        // applied. A profile with voltage, current, cool-down, on-boot or on-plug switched OFF
        // still pushed those stored values to ACC on every apply, because the ConfigUpdaterEnable
        // used at apply time comes from global prefs, not from this profile's pEnables. Clear the
        // same set the editor clears when a section is off, so "off" means off wherever it is read.
        var c = accConfig
        if (!pEnables.eCoolDown)  c = c.copy(configCoolDown = null)
        if (!pEnables.eVoltage)   c = c.copy(configVoltage = AccConfig.ConfigVoltage(null, null))
        if (!pEnables.eCurrMax)   c = c.copy(configCurrMax = null)
        if (!pEnables.eRunOnBoot) c = c.copy(configOnBoot = null)
        if (!pEnables.eRunOnPlug) c = c.copy(configOnPlug = null)
        return applyCapacityToggle(c)
    }

    private fun applyCapacityToggle(accConfig: AccConfig): AccConfig =
        if (pEnables.eCapacity) accConfig
        else accConfig.copy(
            configCapacity = accConfig.configCapacity.copy(
                // BOTH ends, not just pause. accd's _ge_pause_cap is a plain `level >= pause` with
                // no special case for 100, so pause=100 still cuts the moment the battery reaches
                // 100%. Leaving resume at the user's old value (say 70) then makes the phone drain
                // 100 -> 70 and charge back: a 30% cycle, worse than the limit they turned off.
                // resume=99 keeps the hysteresis legal (resume < pause) and collapses the band to
                // 1%, so a full battery simply tops up the way it would with no limit at all.
                pause = AccConfig.ConfigCapacity.DISABLED,
                resume = AccConfig.ConfigCapacity.DISABLED - 1))
    }

    //----------------------------------------------------------------------
    // Enable\disable options in profile for greater flexibility !)

    data class ProfileEnables(
        var eCapacity: Boolean = true,
        var eVoltage: Boolean = false,
        var eCurrMax: Boolean = false,
        var eTemperature: Boolean = true,
        var eCoolDown: Boolean = false,
        var eScripts: Boolean = false,
        var eRunOnBoot: Boolean = false,
        var eRunOnPlug: Boolean = false,
        var eChargingSwitch: Boolean = true, // temporary always ON
    ) : Serializable

