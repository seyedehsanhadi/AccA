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
        var prioritizeBatteryIdleMode: Boolean = false
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

    data class ConfigCapacity(var shutdown: Int = 0, var resume: Int = 60, var pause: Int = 70) : Serializable
    {
        fun toString(context: Context): String
        {
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