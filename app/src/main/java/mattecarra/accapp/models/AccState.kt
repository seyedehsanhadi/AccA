package mattecarra.accapp.models

import org.json.JSONObject

/**
 * Typed view of ACC rc9+ `acca --state` JSON. rc12 emits a schema-versioned, atomic
 * snapshot of the live state — the single source of truth the dashboard prefers over the
 * legacy line-by-line `acca -i` regexes (which read "unplugged while actually plugged"
 * when ACC is cutting, and carry no polarity/lock/measured-class info).
 *
 * Parsing is total: [parseState] returns null on a malformed/empty payload or a
 * schemaVersion below 1, and the caller falls back to the `acca -i` path. Pure data +
 * a pure parser → unit-testable with captured fixtures, no shell.
 *
 * @param schemaVersion the contract version (>= 1 required).
 * @param capacityPct battery level, %.
 * @param currentRaw raw battery current as ACC's sensor reports it (sign/units per [currentUnits]/[polarity]).
 * @param voltageRaw raw battery voltage as reported (µV/mV depending on device).
 * @param tempDeciC battery temperature in deci-°C (e.g. 298 == 29.8 °C).
 * @param status charger status string ("Charging"/"Discharging"/"Not charging"/...).
 * @param plugged present-first plug state (correct even while ACC is cutting; `acca -i` is not).
 * @param currentUnits "uA" or "mA" — the unit of [currentRaw].
 * @param polarity "normal" or "inverted" — whether positive raw means charging or discharging.
 * @param userLocked true when the user manually pinned the charging switch (ACC won't auto-replace it).
 * @param measuredClass the real measured switch behaviour ("charging"/"discharging"/"cut"/"bypass"/...).
 * @param accVersionCode ACC's versionCode, or null if absent/unparseable.
 */
data class AccState(
    val schemaVersion: Int,
    val capacityPct: Int,
    val currentRaw: Long,
    val voltageRaw: Long,
    val tempDeciC: Int,
    val status: String,
    val plugged: Boolean,
    val currentUnits: String,
    val polarity: String,
    val userLocked: Boolean,
    val measuredClass: String,
    val accVersionCode: Int?,
    val nativeEnabled: Boolean,
    val nativeStopLevel: Int
) {

    /**
     * Signed current in milliamps, normalised the same way the daemon documents it:
     * units (uA -> /1000 for mA) and polarity (inverted -> flip sign). A negative result
     * means discharge, positive means charge — independent of the device's raw convention.
     */
    fun signedCurrentMilliAmps(): Float {
        val mA = if (currentUnits.equals("uA", ignoreCase = true)) currentRaw / 1000f else currentRaw.toFloat()
        return if (polarity.equals("inverted", ignoreCase = true)) -mA else mA
    }

    companion object {
        /**
         * Parses an `acca --state` JSON payload. Returns null if the payload is blank,
         * not valid JSON, or its schemaVersion is below 1 (older daemon / unknown contract)
         * so the caller can fall back to the legacy path. Never throws.
         */
        fun parseState(json: String): AccState? {
            if (json.isBlank()) return null
            return try {
                val root = JSONObject(json)
                val schema = root.optInt("schemaVersion", 0)
                if (schema < 1) return null

                val battery = root.optJSONObject("battery") ?: JSONObject()
                val sensing = root.optJSONObject("sensing") ?: JSONObject()
                val sw = root.optJSONObject("switch") ?: JSONObject()
                val acc = root.optJSONObject("acc") ?: JSONObject()
                val native = root.optJSONObject("native") ?: JSONObject()

                AccState(
                    schemaVersion = schema,
                    capacityPct = battery.optInt("capacityPct", -1),
                    currentRaw = battery.optLong("current_raw", 0L),
                    voltageRaw = battery.optLong("voltage_raw", 0L),
                    tempDeciC = battery.optInt("temp_deci_c", -1),
                    status = battery.optString("status", "Unknown"),
                    plugged = root.optBoolean("plugged", false),
                    currentUnits = sensing.optString("currentUnits", "uA"),
                    polarity = sensing.optString("polarity", "normal"),
                    userLocked = sw.optBoolean("userLocked", false),
                    measuredClass = sw.optString("measuredClass", ""),
                    // versionCode is emitted as a string ("202505229"); accept either and
                    // fall back to null so an absent/garbage value never aborts the parse.
                    accVersionCode = acc.optString("versionCode", "").toIntOrNull(),
                    // native firmware %-limit block (Pixel-class); absent on other devices.
                    nativeEnabled = native.optBoolean("enabled", false),
                    nativeStopLevel = native.optInt("stopLevel", -1)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
