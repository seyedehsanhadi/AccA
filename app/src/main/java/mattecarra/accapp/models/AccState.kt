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
    val nativeStopLevel: Int,
    // The daemon emits native.startLevel next to stopLevel; it was parsed nowhere, so the
    // resume half of a firmware limit could not be shown at all.
    val nativeStartLevel: Int = -1,
    val inputVoltageMv: Int? = null,
    val inputCurrentMa: Int? = null,
    val chargeWatts: Int? = null,
    val chargeClass: String? = null,
    val chargeReason: String? = null,
    val chargeApprox: Boolean = false,
    /**
     * The daemon's own view of the config, verbatim. Not parsed into an AccConfig: it is here only
     * so a surface can notice that ACC's config changed underneath it. The dashboard's settings
     * card is fed by SharedViewModel.config, which is posted by AccA's own writers -- so a change
     * from anywhere else (a script from the Scripts tab, a schedule firing through DJS, an edit to
     * config.txt, ACC healing a value) left the card showing numbers ACC did not hold. Measured on
     * a Pixel 6a: the card read "Resume: 70% - Stop: 75%" for minutes while the config said 19 20
     * and the firmware-limit row beside it, fed from --state, already said 19% - 20%.
     */
    val configSignature: String? = null
) {

    /**
     * Signed current in milliamps, normalised the same way the daemon documents it:
     * units (uA -> /1000 for mA) and polarity (inverted -> flip sign). A negative result
     * means discharge, positive means charge — independent of the device's raw convention.
     *
     * polarity "unstable" (rc13+) means the raw sign follows the charge PATH on this device
     * (dual-path PMICs: 5V trickle reads one sign, 9V parallel the other, both charging), so
     * the raw sign carries no meaning. The daemon then classifies by coulomb slope and reports
     * it in [measuredClass]; take the magnitude and let the class/status decide the sign.
     */
    fun signedCurrentMilliAmps(): Float {
        val mA = if (currentUnits.equals("uA", ignoreCase = true)) currentRaw / 1000f else currentRaw.toFloat()
        return normaliseMilliAmps(mA, polarity, measuredClass, status)
    }



    companion object {
        /**
         * The polarity rule, for callers that read the current themselves (the status-bar meter
         * from BatteryManager, the charge capture from sysfs) instead of from this snapshot.
         * It lived only inside [signedCurrentMilliAmps], so those two handled "inverted" and
         * silently mishandled "unstable" -- on a dual-path PMIC (a Pixel reports exactly this)
         * the raw sign follows the charge PATH and means nothing, so both showed the wrong
         * direction. One definition, three callers.
         */
        /**
         * Battery-side watts: the signed mA the caller is already showing, times the pack voltage.
         * NOT charge.watts, which is the CHARGER side (input volts x input amps) and is the larger
         * of the two -- 13 W in from an 8.4 V supply was 11.4 W into a 4.04 V pack on a Pixel 6a.
         * The widget printed the charger figure on a row labelled "To battery"; the dashboard
         * computed this correctly but kept the formula to itself.
         */
        fun batteryWatts(signedMa: Float, voltageRaw: Long): Float {
            val mv = if (voltageRaw >= 100000L) (voltageRaw / 1000L).toInt() else voltageRaw.toInt()
            return if (mv > 1000) signedMa * mv / 1000000f else 0f
        }

        fun normaliseMilliAmps(rawMa: Float, polarity: String?, measuredClass: String?, status: String?): Float =
            when {
                polarity.equals("inverted", ignoreCase = true) -> -rawMa
                polarity.equals("unstable", ignoreCase = true) -> {
                    // The magnitude is trustworthy, the sign is not, so the direction comes from
                    // the same one rule every label uses. It had its own copy that took
                    // measuredClass at face value, which printed +268 mA on a Pixel 6a that was
                    // plugged, held at its native limit and draining.
                    val mag = kotlin.math.abs(rawMa)
                    if (isChargingNow(measuredClass, status)) mag else -mag
                }
                else -> rawMa
            }

        /**
         * Parses an `acca --state` JSON payload. Returns null if the payload is blank,
         * not valid JSON, its schemaVersion is below 1 (older daemon / unknown contract),
         * or it is an ERROR payload, so the caller can fall back to the legacy path.
         * Never throws.
         *
         * The error case is not hypothetical and the schema check alone does not catch it.
         * ACC rc24 can answer with a well-formed, current-schema document that carries NO reading.
         * print_state() emits this when write_state produced no state.json (state-export.sh):
         *
         *     {"schemaVersion":1,"error":"daemon-not-running"}
         *
         * Measured on a Pixel 6a: this is NARROWER than "the daemon is not running". With accd
         * stopped, acca --state still returns a full document, because it can compute the state
         * live. The error branch needs state.json to be absent AND write_state unable to make
         * one - in practice a wiped tmpfs, i.e. early boot before service.sh has run.
         *
         * That makes the SECOND guard below the more valuable of the two: any truncated or
         * partial document has no error key either, and inventing a reading from defaults is
         * worse than admitting there is none.
         *
         * schemaVersion is 1, so the old guard passed it, and every `optJSONObject(...) ?: JSONObject()`
         * below then manufactured an entire battery reading out of defaults: -1%, 0 mA, 0 V,
         * -1 deci-C, unplugged, status "Unknown". Being non-null, it SUPPRESSED the legacy
         * `acca -i` fallback in the dashboard, the widget and the charge meter, so a phone with no
         * daemon showed confident wrong numbers instead of falling back to a path that works.
         *
         * Two conditions reject it: an explicit `error` key, and the absence of the `battery`
         * object. The second matters on its own - a truncated or partial document has no error key
         * either, and a reading invented from defaults is worse than no reading.
         */
        fun parseState(json: String): AccState? {
            if (json.isBlank()) return null
            return try {
                val root = JSONObject(json)
                val schema = root.optInt("schemaVersion", 0)
                if (schema < 1) return null
                if (root.has("error")) return null

                val battery = root.optJSONObject("battery") ?: return null
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
                    nativeStopLevel = native.optInt("stopLevel", -1),
                    nativeStartLevel = native.optInt("startLevel", -1),
                    // charger-INPUT telemetry (rc11+): live input volts/amps, null when the
                    // device has no readable input nodes or the daemon predates the field.
                    inputVoltageMv = root.optJSONObject("input")?.let { inp ->
                        if (inp.isNull("voltageMv")) null else inp.optInt("voltageMv").takeIf { it > 0 }
                    },
                    inputCurrentMa = root.optJSONObject("input")?.let { inp ->
                        if (inp.isNull("currentMa")) null else inp.optInt("currentMa")
                    },
                    // charge-speed block (rc12 engine+): physics-only class from input watts.
                    // Nullable end to end so any older daemon just hides the dashboard line.
                    chargeWatts = root.optJSONObject("charge")?.let { ch ->
                        if (ch.isNull("watts")) null else ch.optInt("watts")
                    },
                    chargeClass = root.optJSONObject("charge")?.optString("class", "")?.takeIf { it.isNotBlank() },
                    chargeReason = root.optJSONObject("charge")?.optString("reason", "")?.takeIf { it.isNotBlank() },
                    chargeApprox = root.optJSONObject("charge")?.optBoolean("approx", false) ?: false,
                    configSignature = root.optJSONObject("config")?.let { cfg ->
                        listOf("capacity", "temperature", "chargingSwitch", "prioritizeBattIdleMode")
                            .joinToString("|") { cfg.optString(it, "") }
                    }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
