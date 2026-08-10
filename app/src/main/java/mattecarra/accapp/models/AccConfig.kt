package mattecarra.accapp.models

import android.content.Context
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import java.io.Serializable

/**
     * Data class for AccConfig.
     * @param configResetUnplugged Reset the battery stats upon unplugging the device.
     * @param configChargeSwitch changes the charge switch file.
     */
    //@Parcelize
    data class AccConfig(
        var configCapacity: ConfigCapacity = ConfigCapacity(),
        var configVoltage: ConfigVoltage = ConfigVoltage(),
        var configCurrMax: Int? = null,
        var configTemperature: ConfigTemperature = ConfigTemperature(),
        var configOnBoot: String? = null,
        var configOnPlug: String? = null,
        var configCoolDown: ConfigCoolDown? = null,
        var configResetUnplugged: Boolean = false,
        var configResetBsOnPause: Boolean = false,
        var configChargeSwitch: String? = null,
        var configIsAutomaticSwitchingEnabled: Boolean = true,
        var prioritizeBatteryIdleMode: Boolean = true
    ) : Serializable
    {

    //    private companion object : Parceler<AccConfig> {
    ////
    ////        override fun create(parcel: Parcel): AccConfig {
    ////            return Gson().fromJson(parcel.readString(), AccConfig::class.java)
    ////        }
    ////
    ////        override fun AccConfig.write(parcel: Parcel, flags: Int) {
    ////            // Convert this to a GSON string
    ////            parcel.writeString(Gson().toJson(this))
    ////        }
    ////    }

    fun getOnPlug(context: Context): String
    {
        return if (configOnPlug.isNullOrBlank()) context.getString(R.string.voltage_control_file_not_set)
        else configOnPlug as String
    }

    /**
     * Capacity Configuration
     * @param shutdown percentage when the device will be shutdown.
     * @param resume percentage when charging should resume.
     * @param pause percentage when charging should be paused.
     */
//    data class ConfigCapacity (var shutdown: Int, var resume: Int, var pause: Int)

    data class ConfigCapacity(var shutdown: Int = 5, var resume: Int = 70, var pause: Int = 75) : Serializable
    {
        companion object {
            // ACC's own "this capacity field is off" sentinel. Anything outside 1..100 (percent)
            // and 3001..5000 (millivolt) makes the daemon's _le_pause_cap/_lt_pause_cap guards
            // return early, so charging is never paused on capacity. ACC ships exactly this in its
            // stock config -- capacity=(5 101 70 75 false) uses 101 to disable cooldown_capacity.
            // Reusing it means disabling needs no new config key, no DB migration, and no AccA-side
            // flag that could drift from what ACC actually does.
            // 100, not 101. ACC validates pause_capacity as 1..100 or 3001..5000 and clamps anything else
            // to 80, so sending 101 to mean "disabled" did not disable anything -- it silently set the
            // user's limit to 80%. Measured on a Pixel 6a: `acc -s pause_capacity=101` produced
            // capacity=(5 101 70 80 false), while pause_capacity=100 is accepted verbatim and means
            // "charge to full", which is exactly capacity control switched off. 101 is the disabled
            // sentinel for COOLDOWN capacity, not for pause; reusing it here was the bug.
            const val DISABLED = 100
        }

        /**
         * True when ACC will actually act on this capacity limit. A pause value outside both the
         * percent and millivolt domains is ACC's way of saying "ignore capacity", which is what a
         * user asking for temperature-only control wants.
         */
        val isEnabled: Boolean
            // The sentinel is the PAIR, not pause alone.
            //
            // Reading pause == 100 as "off" on its own was wrong, and users hit it: setting the
            // pause level to 100 made the whole capacity row vanish from the dashboard and read as
            // disabled in the editor, even though shutdown_capacity and resume_capacity were still
            // being enforced. Charging to 100 and resuming at, say, 70 is a real setting -- it is
            // the top-off behaviour a lot of people want -- and hiding it loses the two values that
            // are still live.
            //
            // Off is written as pause=100 AND resume=99 (see disable()), a 1% band that enforces
            // nothing, so require both before calling it disabled. That leaves every other pause
            // value, 100 included, visible with its own numbers.
            get() = (pause in 1..100 || pause in 3001..5000) && !(pause == DISABLED && resume == DISABLED - 1)

        /**
         * Turn capacity control off without losing the user's previous numbers.
         *
         * BOTH ends move. accd's _ge_pause_cap is a plain `level >= pause` with no special case for
         * 100, so pause=100 alone still cuts at a full battery; leaving resume at 70 would then
         * drain 100 -> 70 and charge back, a 30% cycle worse than the limit being turned off.
         */
        fun disable() { pause = DISABLED; resume = DISABLED - 1 }

        fun toString(context: Context): String
        {
            if (!isEnabled) return context.getString(R.string.capacity_control_disabled)
            // pause > 100 = ACC's millivolt capacity domain; show mV instead of % so the dashboard and
            // profile cards are not mislabeled "100%" for a voltage-based limit.
            val tmpl = if (pause > 100) R.string.template_capacity_profile_mv else R.string.template_capacity_profile
            return String.format(context.getString(tmpl), shutdown, resume, pause)
        }
    }

    /**
     * Voltage Configuration
     * @param controlFile path to the device's voltage control file.
     * @param max the max voltage the device should take from the charger.
     */
    data class ConfigVoltage(var controlFile: String? = null, var max: Int? = null) : Serializable
    {
        fun toString(context: Context): String
        {
            return if (Acc.instance.version >= 202002170) context.getString(R.string.voltage_max) +" "+ (max.toString() ?: "-")
            else context.getString(R.string.voltage_control_file) +" "+ (controlFile.toString() ?: "-")
        }
    }

    /**
     * Temperature Configuration.
     * Default 45 / 50 / 40 (cool-down / max / resume, all °C) — aligned with the active
     * v202107280 parser fallback so a fresh config and a parse-fallback agree.
     * @param coolDownTemperature temperature at which the cool-down phase starts.
     * @param maxTemperature pause charging when the battery reaches this temperature.
     * @param pause resume temperature: resume charging once the battery cools to this
     *        (this maps to ACC's resume_temp in °C; the field name is legacy).
     * @param shutdown over-temperature cutoff: ACC shuts the device down at this
     *        temperature (ACC's shutdown_temp in °C). Default 55 matches the daemon.
     */
    data class ConfigTemperature(var coolDownTemperature: Int = 45, var maxTemperature: Int = 50, var pause: Int = 40, var shutdown: Int = 55) : Serializable
    {
        fun toString(context: Context): String
        {
            return String.format(context.getString(
                    R.string.template_temperature_profile,
                    coolDownTemperature, maxTemperature, pause
                ))
        }
    }

    /**
     * Cool Down configuration.
     * Default set as 60/50/10.
     * @param atPercent coolDown starts at the specified percent.
     * @param charge charge time in seconds.
     * @param pause pause time in seconds.
     */
    data class ConfigCoolDown(var atPercent: Int = 60, var charge: Int = 50, var pause: Int = 10) : Serializable
    {
        fun toString(context: Context): String
        {
            return context.getString(R.string.template_cool_down_profile, atPercent, charge, pause)
        }
    }
}