package mattecarra.accapp.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.ProfileEnables

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
        catch (e: Exception) { e.printStackTrace(); ProfileEnables() }
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
        catch (e: Exception) { e.printStackTrace(); null }
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
        catch (e: Exception) { e.printStackTrace(); AccConfig.ConfigCapacity() }
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
        catch (e: Exception) { e.printStackTrace(); AccConfig.ConfigVoltage() }
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
        return try { Gson().fromJson(configTemperature, AccConfig.ConfigTemperature::class.java) ?: AccConfig.ConfigTemperature() }
        catch (e: Exception) { e.printStackTrace(); AccConfig.ConfigTemperature() }
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
        catch (e: Exception) { e.printStackTrace(); null }
    }
}