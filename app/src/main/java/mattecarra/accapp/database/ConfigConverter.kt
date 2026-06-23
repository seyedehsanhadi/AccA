package mattecarra.accapp.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.ProfileEnables
import mattecarra.accapp.utils.LogExt

// Every to*() converter parses JSON stored in the DB. A corrupted/legacy/empty
// value (Gson can return null or throw JsonSyntaxException) must NOT crash the
// query that loads profiles or schedules. Each parse is wrapped and falls back
// to a sane default so the row degrades gracefully instead of taking down the app.
object ConfigConverter
{

    @TypeConverter
    @JvmStatic
    fun fromEnables(enables: ProfileEnables): String
    {
        return Gson().toJson(enables)
    }

    @TypeConverter
    @JvmStatic
    fun toEnables(enables: String): ProfileEnables
    {
        return try { Gson().fromJson(enables, ProfileEnables::class.java) ?: ProfileEnables() }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toEnables parse failed: $e"); ProfileEnables() }
    }

    @TypeConverter
    @JvmStatic
    fun fromScripts(scripts: List<Int>?): String?
    {
        return Gson().toJson(scripts)
    }

    @TypeConverter
    @JvmStatic
    fun toScripts(scripts: String?): List<Int>?
    {
        return try { Gson().fromJson(scripts, object : TypeToken<List<Int>>() {}.type) }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toScripts parse failed: $e"); null }
    }

    @TypeConverter
    @JvmStatic
    fun fromConfigCapacity(configCapacity: AccConfig.ConfigCapacity): String
    {
        return Gson().toJson(configCapacity)
    }

    @TypeConverter
    @JvmStatic
    fun toConfigCapacity(configCapacity: String): AccConfig.ConfigCapacity
    {
        return try { Gson().fromJson(configCapacity, AccConfig.ConfigCapacity::class.java) ?: AccConfig.ConfigCapacity() }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toConfigCapacity parse failed: $e"); AccConfig.ConfigCapacity() }
    }

    @TypeConverter
    @JvmStatic
    fun fromConfigVoltage(configVoltage: AccConfig.ConfigVoltage) : String
    {
        return Gson().toJson(configVoltage)
    }

    @TypeConverter
    @JvmStatic
    fun toConfigVoltage(configVoltage: String) : AccConfig.ConfigVoltage
    {
        return try { Gson().fromJson(configVoltage, AccConfig.ConfigVoltage::class.java) ?: AccConfig.ConfigVoltage() }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toConfigVoltage parse failed: $e"); AccConfig.ConfigVoltage() }
    }

    @TypeConverter
    @JvmStatic
    fun fromConfigTemperature(configTemperature: AccConfig.ConfigTemperature) : String
    {
        return Gson().toJson(configTemperature)
    }

    @TypeConverter
    @JvmStatic
    fun toConfigTemperature(configTemperature: String) : AccConfig.ConfigTemperature
    {
        return try {
            val parsed = Gson().fromJson(configTemperature, AccConfig.ConfigTemperature::class.java) ?: AccConfig.ConfigTemperature()
            // Gson bypasses the Kotlin default constructor, so a profile saved before the
            // shutdown_temp field existed deserializes with shutdown=0 (JVM zero), which the
            // editor's [40..70] validation would reject. Restore ACC's default when absent.
            if (parsed.shutdown <= 0) parsed.copy(shutdown = 55) else parsed
        }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toConfigTemperature parse failed: $e"); AccConfig.ConfigTemperature() }
    }

    @TypeConverter
    @JvmStatic
    fun fromConfigCoolDown(configCoolDown: AccConfig.ConfigCoolDown?) : String
    {
        return Gson().toJson(configCoolDown)
    }

    @TypeConverter
    @JvmStatic
    fun toConfigCoolDown(configCoolDown: String) : AccConfig.ConfigCoolDown?
    {
        return try { Gson().fromJson(configCoolDown, AccConfig.ConfigCoolDown::class.java) }
        catch (e: Exception) { LogExt().e("ConfigConverter", "toConfigCoolDown parse failed: $e"); null }
    }
}