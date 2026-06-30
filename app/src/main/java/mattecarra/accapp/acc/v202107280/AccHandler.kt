package mattecarra.accapp.acc.v202107280

import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mattecarra.accapp.acc.ConfigUpdateResult
import mattecarra.accapp.acc.ConfigUpdater
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.acc._interface.AccInterface
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.BatteryInfo
import mattecarra.accapp.utils.LogExt
import java.io.IOException
import java.util.regex.Pattern

open class AccHandler(override val version: Int) : AccInterface {
    // String resources
    private val STRING_UNKNOWN = "Unknown"
    private val STRING_NOT_CHARGING = "Not charging"
    private val STRING_DISCHARGING = "Discharging"
    private val STRING_CHARGING = "Charging"

    // RegEx Values
    // Capacity
    val SHUTDOWN_CAPACITY_REGEXP = """^\s*shutdown_capacity=(\d*)""".toRegex(RegexOption.MULTILINE)
    val COOLDOWN_CAPACITY_REGEXP = """^\s*cooldown_capacity=(\d*)""".toRegex(RegexOption.MULTILINE)
    val RESUME_CAPACITY_REGEXP = """^\s*resume_capacity=(\d*)""".toRegex(RegexOption.MULTILINE)
    val PAUSE_CAPACITY_REGEXP = """^\s*pause_capacity=(\d*)""".toRegex(RegexOption.MULTILINE)

    // Cool Down
    val COOLDOWN_TEMP_REGEXP = """^\s*cooldown_temp=(\d*)""".toRegex(RegexOption.MULTILINE)
    val MAX_TEMP_REGEXP = """^\s*max_temp=(\d*)""".toRegex(RegexOption.MULTILINE)
    val MAX_TEMP_PAUSE_REGEXP = """^\s*(?:max_temp_pause|resume_temp)=(\d*)""".toRegex(RegexOption.MULTILINE)
    val SHUTDOWN_TEMP_REGEXP = """^\s*shutdown_temp=(\d*)""".toRegex(RegexOption.MULTILINE)

    val COOLDOWN_CHARGE_REGEXP = """^\s*cooldown_charge=(\d*)""".toRegex(RegexOption.MULTILINE)
    val COOLDOWN_PAUSE_REGEXP  = """^\s*cooldown_pause=(\d*)""".toRegex(RegexOption.MULTILINE)

    // Plugged/Pause
    val RESET_UNPLUGGED_CONFIG_REGEXP = """^\s*reset_batt_stats_on_unplug=(true|false)""".toRegex(RegexOption.MULTILINE)
    val RESET_ON_PAUSE_CONFIG_REGEXP = """^\s*reset_batt_stats_on_pause=(true|false)""".toRegex(RegexOption.MULTILINE)
    val ON_BOOT = """^\s*apply_on_boot=((?:(?!#).)*)""".toRegex(RegexOption.MULTILINE)
    val ON_PLUGGED = """^\s*apply_on_plug=((?:(?!#).)*)""".toRegex(RegexOption.MULTILINE)

    val MAX_CHARGING_VOLTAGE = """^\s*max_charging_voltage=(\d*)""".toRegex(RegexOption.MULTILINE)
    val MAX_CHARGING_CURRENT = """^\s*max_charging_current=(\d*)""".toRegex(RegexOption.MULTILINE)

    val SWITCH = """^\s*charging_switch=((?:(?!#).)*)""".toRegex(RegexOption.MULTILINE)
    val AUTOMATIC_SWITCH_DISABLED = """^(.*) --\s*""".toRegex(RegexOption.MULTILINE)
    val PRIORITIZE_BATTERY_IDLE = """^\s*prioritize_batt_idle_mode=(true|false)""".toRegex(RegexOption.MULTILINE)

    @WorkerThread
    fun parseConfig(config: String): AccConfig {
        // Robustness: ACC renames/drops config keys between releases (e.g. max_temp_pause ->
        // resume_temp in 2025.x) and a custom/minimal acc.conf may omit keys entirely, while a
        // present-but-empty key (regex group is \d*) matches "" and used to crash .toInt().
        // Read every value null-safely and fall back to ACC's documented defaults so the parser
        // can never throw, regardless of which ACC version produced the config.
        fun matchInt(regex: Regex): Int? =
            regex.find(config)?.destructured?.component1()?.toIntOrNull()

        val capacityShutdown = matchInt(SHUTDOWN_CAPACITY_REGEXP) ?: 0
        val capacityCoolDown = matchInt(COOLDOWN_CAPACITY_REGEXP) ?: 101
        val capacityResume   = matchInt(RESUME_CAPACITY_REGEXP) ?: 70
        val capacityPause    = matchInt(PAUSE_CAPACITY_REGEXP) ?: 75

        // Fallbacks are ACC's documented defaults (cooldown_temp 45, max_temp 50, resume_temp 40)
        // -- NOT the legacy 90/95 placeholders. A missing max_temp must never load as 95 C, which
        // would mean no thermal pause at all; and the third field is resume_temp in 2025.x, not a
        // 90-second wait, so it defaults to 40 C.
        val temperatureCooldown = matchInt(COOLDOWN_TEMP_REGEXP) ?: 45
        val temperatureMax      = matchInt(MAX_TEMP_REGEXP) ?: 50
        val waitSeconds         = matchInt(MAX_TEMP_PAUSE_REGEXP) ?: 40
        // shutdown_temp is the over-temperature cutoff (°C). Default 55 = ACC's documented
        // default, so a config that omits the key never loads as "no cutoff".
        val shutdownTemp        = matchInt(SHUTDOWN_TEMP_REGEXP) ?: 55

        val coolDownChargeSeconds = COOLDOWN_CHARGE_REGEXP.find(config)?.destructured?.component1()?.toIntOrNull()
        val coolDownPauseSeconds = COOLDOWN_PAUSE_REGEXP.find(config)?.destructured?.component1()?.toIntOrNull()

        val maxChargingVoltage = MAX_CHARGING_VOLTAGE.find(config)?.destructured?.component1()
        val maxChargingCurrent = MAX_CHARGING_CURRENT.find(config)?.destructured?.component1()

        return AccConfig(
            AccConfig.ConfigCapacity(capacityShutdown, capacityResume, capacityPause),
            AccConfig.ConfigVoltage(null, maxChargingVoltage?.toIntOrNull()),
            maxChargingCurrent?.toIntOrNull(),
            AccConfig.ConfigTemperature(temperatureCooldown, temperatureMax, waitSeconds, shutdownTemp),
            getOnBoot(config),
            getOnPlugged(config),
            if(coolDownChargeSeconds != null && coolDownPauseSeconds != null)
                AccConfig.ConfigCoolDown(capacityCoolDown, coolDownChargeSeconds, coolDownPauseSeconds)
            else null,
            getResetUnplugged(config),
            getResetOnPause(config),
            getCurrentChargingSwitch(config),
            isAutomaticSwitchEnabled(config),
            isPrioritizeBatteryIdleMode(config)
        )
    }

    override suspend fun readConfig(): AccConfig = withContext(Dispatchers.IO) {
        parseConfig(readConfigToString())
    }

    override suspend fun readDefaultConfig(): AccConfig = withContext(Dispatchers.IO) {
        val defaultConfig = Shell.su("/dev/.vr25/acc/acca --set --print-default").exec().out.joinToString(separator = "\n")

        parseConfig(defaultConfig)
    }

    @Throws(IOException::class)
    @WorkerThread
    open fun readConfigToString(): String {
        return Shell.su("/dev/.vr25/acc/acca --set --print").exec().out.joinToString(separator = "\n")
    }

    // Returns OnBoot value
    private fun getOnBoot(config: CharSequence) : String? {
        return ON_BOOT.find(config)?.destructured?.component1()?.trim()?.removeSurrounding("\"")?.trim()?.ifBlank { null }
    }

    // Returns OnPlugged value
    private fun getOnPlugged(config: CharSequence) : String? {
        return ON_PLUGGED.find(config)?.destructured?.component1()?.trim()?.removeSurrounding("\"")?.trim()?.ifBlank { null }
    }

    // Returns ResetUnplugged value
    private fun getResetUnplugged(config: CharSequence) : Boolean {
        return RESET_UNPLUGGED_CONFIG_REGEXP.find(config)?.destructured?.component1() == "true"
    }

    // Returns ResetOnPause value
    private fun getResetOnPause(config: CharSequence) : Boolean {
        return RESET_ON_PAUSE_CONFIG_REGEXP.find(config)?.destructured?.component1() == "true"
    }

    override suspend fun listVoltageSupportedControlFiles(): List<String> = withContext(Dispatchers.IO) {
        val res = Shell.su("/dev/.vr25/acc/acca -v :").exec()

        if(res.isSuccess)
            res.out.filter { it.isNotEmpty() }
        else
            emptyList()
    }

    override suspend fun resetBatteryStats(): Boolean = withContext(Dispatchers.IO) {
        Shell.su("/dev/.vr25/acc/acca -R").exec().isSuccess
    }

    /**
     * Regex for acc -i (info)
     */
    // Regex for determining NAME of BATTERY
    private val NAME_REGEXP = """^\s*NAME=([a-zA-Z0-9]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for INPUT_SUSPEND
    private val INPUT_SUSPEND_REGEXP = """^\s*INPUT_SUSPEND=([01])""".toRegex(RegexOption.MULTILINE)
    private val STATUS_REGEXP = """^\s*STATUS=(${STRING_CHARGING}|${STRING_DISCHARGING}|${STRING_NOT_CHARGING})""".toRegex(RegexOption.MULTILINE)
    private val HEALTH_REGEXP = """^\s*HEALTH=([a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for PRESENT value
    private val PRESENT_REGEXP = """^\s*PRESENT=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for determining CHARGE_TYPE
    private val CHARGE_TYPE_REGEXP = """^\s*CHARGE_TYPE=(N/A|[a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for battery CAPACITY
    private val CAPACTIY_REGEXP = """^\s*CAPACITY=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CHARGER_TEMP
    private val CHARGER_TEMP_REGEXP = """^\s*CHARGER_TEMP=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CHARGER_TEMP_MAX
    private val CHARGER_TEMP_MAX_REGEXP = """^\s*CHARGER_TEMP_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for INPUT_CURRENT_LIMITED, 0 = false, 1 = true
    private val INPUT_CURRENT_LIMITED_REGEXP = """^\s*INPUT_CURRENT_LIMITED=([01])""".toRegex(RegexOption.MULTILINE)
    private val VOLTAGE_NOW_REGEXP = """^\s*VOLTAGE_NOW=([+-]?([0-9]*[.])?[0-9]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for VOLTAGE_MAX
    private val VOLTAGE_MAX_REGEXP = """^\s*VOLTAGE_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for VOLTAGE_QNOVO
    private val VOLTAGE_QNOVO_REGEXP = """^\s*VOLTAGE_QNOVO=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CURRENT_NOW_REGEXP = """^\s*CURRENT_NOW=([+-]?([0-9]*[.])?[0-9]+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CURRENT_QNOVO
    private val CURRENT_QNOVO_REGEXP = """^\s*CURRENT_NOW=(-?\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for CONSTANT_CHARGE_CURRENT_MAX
    private val CONSTANT_CHARGE_CURRENT_MAX_REGEXP = """^\s*CONSTANT_CHARGE_CURRENT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val TEMP_REGEXP = """^\s*TEMP=(\d+)""".toRegex(RegexOption.MULTILINE)
    // Regex for remaining 'acc -i' values
    private val TECHNOLOGY_REGEXP = """^\s*TECHNOLOGY=([a-zA-Z\-]+)""".toRegex(RegexOption.MULTILINE)
    private val STEP_CHARGING_ENABLED_REGEXP = """^\s*STEP_CHARGING_ENABLED=([01])""".toRegex(RegexOption.MULTILINE)
    private val SW_JEITA_ENABLED_REGEXP = """^\s*SW_JEITA_ENABLED=([01])""".toRegex(RegexOption.MULTILINE)
    private val TAPER_CONTROL_ENABLED_REGEXP = """^\s*TAPER_CONTROL_ENABLED=([01])""".toRegex(RegexOption.MULTILINE)
    // CHARGE_DISABLE is true when ACC disables charging due to conditions
    private val CHARGE_DISABLE_REGEXP = """^\s*CHARGE_DISABLE=([01])""".toRegex(RegexOption.MULTILINE)
    // CHARGE_DONE is true when the battery is done charging.
    private val CHARGE_DONE_REGEXP = """^\s*CHARGE_DONE=([01])""".toRegex(RegexOption.MULTILINE)
    private val PARALLEL_DISABLE_REGEXP = """^\s*PARALLEL_DISABLE=([01])""".toRegex(RegexOption.MULTILINE)
    private val SET_SHIP_MODE_REGEXP = """^\s*SET_SHIP_MODE=([01])""".toRegex(RegexOption.MULTILINE)
    private val DIE_HEALTH_REGEXP = """^\s*DIE_HEALTH=([a-zA-Z]+)""".toRegex(RegexOption.MULTILINE)
    private val RERUN_AICL_REGEXP = """^\s*RERUN_AICL=([01])""".toRegex(RegexOption.MULTILINE)
    private val DP_DM_REGEXP = """^\s*DP_DM=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_CONTROL_LIMIT_MAX_REGEXP = """^\s*CHARGE_CONTROL_LIMIT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_CONTROL_LIMIT_REGEXP = """^\s*CHARGE_CONTROL_LIMIT=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_COUNTER_REGEXP = """^\s*CHARGE_COUNTER=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val INPUT_CURRENT_MAX_REGEXP = """^\s*INPUT_CURRENT_MAX=(\d+)""".toRegex(RegexOption.MULTILINE)
    private val CYCLE_COUNT_REGEXP = """^\s*CYCLE_COUNT=(\d+)""".toRegex(RegexOption.MULTILINE)

    private val POWER_NOW_REGEXP = """^\s*POWER_NOW=([+-]?([0-9]*[.])?[0-9]+)""".toRegex(RegexOption.MULTILINE)

    /**
     * ACC 2025.x rewrote `acca -i` to a lowercase, unit-suffixed format, e.g.
     *   level 23%
     *   status Charging
     *   temp 35℃
     *   voltage_now 4.10V
     *   current_now 3.605A
     *   power_now 14.5W
     *   charge_type <type>
     * The uppercase KEY=value regexes above only match the older uevent-style
     * output, so on ACC 2025.x every field fell back to its default (-1 / 0 /
     * Unknown) and the dashboard showed an empty battery. These read the new
     * format as a fallback. Values already arrive pre-scaled to %/V/A/°C, which
     * is exactly what AccA expects for ACC >= 202002292 (volts + amps).
     */
    private val LEVEL_LOWER_REGEXP = """^\s*level (\d+)""".toRegex(RegexOption.MULTILINE)
    private val STATUS_LOWER_REGEXP = """^\s*status (.+)""".toRegex(RegexOption.MULTILINE)
    private val TEMP_LOWER_REGEXP = """^\s*temp (\d+)""".toRegex(RegexOption.MULTILINE)
    private val VOLTAGE_NOW_LOWER_REGEXP = """^\s*voltage_now ([0-9]*\.?[0-9]+)""".toRegex(RegexOption.MULTILINE)
    private val CURRENT_NOW_LOWER_REGEXP = """^\s*current_now (-?[0-9]*\.?[0-9]+)""".toRegex(RegexOption.MULTILINE)
    private val POWER_NOW_LOWER_REGEXP = """^\s*power_now (-?[0-9]*\.?[0-9]+)""".toRegex(RegexOption.MULTILINE)
    private val CHARGE_TYPE_LOWER_REGEXP = """^\s*charge_type (.+)""".toRegex(RegexOption.MULTILINE)

    private fun lowerStatus(info: String): String? =
        STATUS_LOWER_REGEXP.find(info)?.destructured?.component1()?.trim()?.let {
            when {
                it.contains("Not", true) -> STRING_NOT_CHARGING
                // ACC reports "Idle" (battery-idle hold) and "Not charging" for a plugged-but-
                // halted battery; both map to the not-charging label so the dashboard never
                // mislabels an idle/cut hold as discharging on the legacy -i fallback path.
                it.equals("Idle", true) -> STRING_NOT_CHARGING
                it.equals("Not charging", true) -> STRING_NOT_CHARGING
                it.equals("Discharging", true) -> STRING_DISCHARGING
                it.equals("Charging", true) -> STRING_CHARGING
                else -> it
            }
        }

    override suspend fun getBatteryInfo(): BatteryInfo = withContext(Dispatchers.IO) {
        // Capture the Shell.Result so a failed `acca -i` (no root yet, acc absent,
        // non-zero exit) is logged. We STILL parse/return below: on failure `info`
        // is just empty, so every field falls back to its documented default.
        val infoRes = Shell.su("/dev/.vr25/acc/acca -i").exec()
        if (!infoRes.isSuccess)
            LogExt().e("AccHandler", "acca -i failed (code=${infoRes.code}): ${infoRes.err.joinToString("\n")}")
        val info = infoRes.out.joinToString(separator = "\n")

        // Battery health is absent from ACC 2025.x's `acca -i`, so read the kernel's
        // generic power_supply node directly. Crash-safe: any failure (no root yet,
        // node missing on this device/kernel, empty value) yields null -> Unknown.
        val kernelHealth: String? = try {
            Shell.su("cat /sys/class/power_supply/battery/health").exec().let {
                if (it.isSuccess) it.out.firstOrNull()?.trim()?.ifBlank { null } else null
            }
        } catch (e: Exception) { null }

        BatteryInfo(
            NAME_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            // INPUT_SUSPEND=1 means charging input IS suspended (true).
            INPUT_SUSPEND_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            STATUS_REGEXP.find(info)?.destructured?.component1() ?: lowerStatus(info) ?: STRING_DISCHARGING,
            HEALTH_REGEXP.find(info)?.destructured?.component1() ?: kernelHealth ?: STRING_UNKNOWN,
            PRESENT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGE_TYPE_REGEXP.find(info)?.destructured?.component1() ?: CHARGE_TYPE_LOWER_REGEXP.find(info)?.destructured?.component1()?.trim() ?: STRING_UNKNOWN,
            CAPACTIY_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: LEVEL_LOWER_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGER_TEMP_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: -1,
            CHARGER_TEMP_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: -1,
            INPUT_CURRENT_LIMITED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            VOLTAGE_NOW_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: VOLTAGE_NOW_LOWER_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: 0f,
            VOLTAGE_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            VOLTAGE_QNOVO_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CURRENT_NOW_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: CURRENT_NOW_LOWER_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: 0f,
            CURRENT_QNOVO_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CONSTANT_CHARGE_CURRENT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            TEMP_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull()?.let { it/10 } ?: TEMP_LOWER_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            TECHNOLOGY_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            STEP_CHARGING_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            SW_JEITA_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            TAPER_CONTROL_ENABLED_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            CHARGE_DISABLE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            CHARGE_DONE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            PARALLEL_DISABLE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            SET_SHIP_MODE_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            DIE_HEALTH_REGEXP.find(info)?.destructured?.component1() ?: STRING_UNKNOWN,
            RERUN_AICL_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() == 1,
            // DP_DM is a numeric control code; any non-zero value means active.
            (DP_DM_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: 0) != 0,
            CHARGE_CONTROL_LIMIT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CHARGE_CONTROL_LIMIT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            INPUT_CURRENT_MAX_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            CYCLE_COUNT_REGEXP.find(info)?.destructured?.component1()?.toIntOrNull() ?: -1,
            POWER_NOW_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: POWER_NOW_LOWER_REGEXP.find(info)?.destructured?.component1()?.toFloatOrNull() ?: 0f
        )
    }

    /**
     * rc9+ structured state. Runs `acca --state`, joins stdout, and parses the JSON via
     * [AccState.parseState] (which returns null on schemaVersion < 1 or any parse error).
     * Crash-safe: any shell failure yields null so the dashboard falls back to `acca -i`.
     */
    override suspend fun getState(): AccState? = withContext(Dispatchers.IO) {
        try {
            val res = Shell.su("/dev/.vr25/acc/acca --state").exec()
            if (!res.isSuccess) return@withContext null
            AccState.parseState(res.out.joinToString(separator = "\n"))
        } catch (e: Exception) {
            LogExt().e("AccHandler", "acca --state failed: ${e.message}")
            null
        }
    }

    override suspend fun isBatteryCharging(): Boolean = withContext(Dispatchers.IO) {
        val info = Shell.su("/dev/.vr25/acc/acca -i").exec().out.joinToString("\n")
        // ACC 2025.x emits lowercase 'status Charging'; the uppercase STATUS=
        // regex alone reported not-charging on those builds, so fall back to the
        // same lowerStatus() helper getBatteryInfo uses.
        val status = STATUS_REGEXP.find(info)?.destructured?.component1() ?: lowerStatus(info)
        status == STRING_CHARGING
    }

    override suspend fun isAccdRunning(): Boolean = withContext(Dispatchers.IO) {
        val code = Shell.su("/dev/.vr25/acc/acca -D").exec().code
        code == 0 || code == 8
    }

    override suspend fun abcStartDaemon(): Boolean = withContext(Dispatchers.IO) {
        Shell.su("/dev/.vr25/acc/acca -D start").exec().isSuccess
    }

    override fun getAccRestartDaemon(): String =  "/dev/.vr25/acc/acca -D restart"

    override suspend fun abcStopDaemon(): Boolean = withContext(Dispatchers.IO) {
        Shell.su("/dev/.vr25/acc/accd.").exec().isSuccess
    }

    //Charging switches
    override suspend fun listChargingSwitches(): List<String> = withContext(Dispatchers.IO) {
        val res = Shell.su("/dev/.vr25/acc/acca -s s:").exec()

        if(res.isSuccess)
            res.out.map { it.trim() }.filter { it.isNotEmpty() }
        else
            emptyList()
    }

    // A switch "works" when `acca -t` reports success. ACC returns exit 0 for a working CUT
    // switch but exit 15 for a working IDLE/BYPASS switch (by design: acc.sh does
    // `$idleMode && return 15 || return 0`), and prints "Switch works" on success either way.
    // Treat all three signals as a pass so a working bypass switch is no longer rejected; the
    // not-charging case (exit 2) and a genuine failure (exit 1) stay non-passing.
    private fun switchTestPassed(code: Int, out: List<String>): Boolean =
        code == 0 || code == 15 || out.any { it.contains("Switch works", ignoreCase = true) }

    override suspend fun testChargingSwitch(chargingSwitch: String?): Int = withContext(Dispatchers.IO) {
        try {
            // Hard-bound the test (see ensureDaemonRunning): it stops the daemon
            // and can otherwise run for minutes, wedging the shell.
            val res = Shell.su("timeout 150 /dev/.vr25/acc/acca -t${chargingSwitch?.let{" $it"} ?: ""}").exec()
            // Normalise a working switch to 0 so every caller's `== 0` success check stays correct
            // AND now accepts the exit-15 bypass case. Non-passing codes (2 = plug in, 1 = fails)
            // pass through unchanged so callers can still tell those apart.
            if (switchTestPassed(res.code, res.out)) 0 else res.code
        } finally {
            ensureDaemonRunning()
        }
    }

    override fun getCurrentChargingSwitch(config: String): String? {
        return SWITCH.find(config)?.destructured?.component1()?.trim()?.removeSurrounding("\"")?.trim()?.removeSuffix(" --")?.trim()?.ifBlank { null }
    }

    override fun isAutomaticSwitchEnabled(config: String): Boolean {
        // Only the charging_switch line decides automatic mode. ACC marks a switch
        // manual by appending " --" to it. Matching " --" against the WHOLE config
        // wrongly reported automatic-off whenever apply_on_boot / apply_on_plug (or
        // any other line) ended in " --". Check the charging_switch value alone.
        val sw = SWITCH.find(config)?.destructured?.component1()?.trim()?.removeSurrounding("\"")?.trim()
            ?: return true
        return !sw.endsWith(" --")
    }

    override fun isPrioritizeBatteryIdleMode(config: String): Boolean {
        return PRIORITIZE_BATTERY_IDLE.find(config)?.destructured?.component1()?.toBoolean() ?: true
    }

    override suspend fun setChargingLimitForOneCharge(limit: Int): Boolean = withContext(Dispatchers.IO) {
        // `acc -f` blocks for the entire charge, so it must stay backgrounded. The bare-PATH
        // `command -v acc` probe silently no-ops on KernelSU (acc is not on PATH there), so use
        // the absolute binary path used everywhere else in this handler. isSuccess reflects
        // whether the command could be launched (catches acc absent) while the long charge
        // itself still runs non-blocking in the background. "true" = launched, not done.
        Shell.su("(/dev/.vr25/acc/acca -f $limit &)").exec().isSuccess
    }

    // Structural, case-insensitive match so a reworded ACC probe line doesn't
    // silently disable the prioritize-idle toggle (ACC output has changed before).
    val BATTERY_IDLE_SUPPORTED = """(?i)batt.?idle.?mode\s*[=:]?\s*true""".toPattern(Pattern.MULTILINE)
    /**
     * SAFETY: `acc -t` STOPS the charge-control daemon while it tests switches,
     * so the configured stop level is NOT enforced during a test, and if the
     * test is killed (the app's root shell is force-closed -> SIGTERM) ACC's own
     * deferred restart can be skipped, leaving charging UNCONTROLLED.
     *
     * After every test we therefore guarantee the daemon is running again: wait
     * past ACC's own ~2s restart window, then force a restart only if it is still
     * down. Fail-safe: the worst case is restarting an already-running daemon, and
     * the configured limit is always enforced again. Runs on a worker thread.
     */
    private fun ensureDaemonRunning() {
        try {
            Thread.sleep(2500)
            val code = Shell.su("/dev/.vr25/acc/acca -D").exec().code
            // isAccdRunning() treats 0 and 8 as "running"; anything else = down.
            if (code != 0 && code != 8)
                Shell.su("/dev/.vr25/acc/acca -D restart").exec()
        } catch (e: Exception) {
            // Last resort: attempt a restart so charging never stays uncontrolled.
            try { Shell.su("/dev/.vr25/acc/acca -D restart").exec() } catch (_: Exception) {}
        }
    }

    override suspend fun isBatteryIdleSupported(): Pair<Int, Boolean> = withContext(Dispatchers.IO) {
        try {
            // `timeout` (toybox) hard-bounds the test so it can never wedge the
            // root shell and block other commands (-D, -v, diagnostics).
            val res = Shell.su("timeout 60 /dev/.vr25/acc/acca -t --").exec()
            Pair(
                res.code,
                BATTERY_IDLE_SUPPORTED.matcher(res.out.joinToString("\n")).find()
            )
        } finally {
            ensureDaemonRunning()
        }
    }

    //Update config part:

    /**
     * Function takes in AccConfig file and will apply it.
     * @param accConfig Configuration file to apply.
     * @return ConfigUpdateResult data class.
     */
    override suspend fun updateAccConfig(accConfig: AccConfig, cue: ConfigUpdaterEnable): ConfigUpdateResult {
        return ConfigUpdater(accConfig, cue)
            .execute(this)
    }

    override fun getUpdateResetUnpluggedCommand(resetUnplugged: Boolean) = "/dev/.vr25/acc/acca -s reset_batt_stats_on_unplug=$resetUnplugged"

    override fun getUpdateResetOnPauseCommand(resetOnPause: Boolean) = "/dev/.vr25/acc/acca -s reset_batt_stats_on_pause=$resetOnPause"

    override fun getUpdateAccCoolDownCommand(charge: Int?, pause: Int?): String = "/dev/.vr25/acc/acca -s cooldown_charge=${charge?.toString().orEmpty()} cooldown_pause=${pause?.toString().orEmpty()}"

    override fun getUpdateAccCapacityCommand(shutdown: Int, coolDown: Int, resume: Int, pause: Int): String = "/dev/.vr25/acc/acca -s shutdown_capacity=$shutdown cooldown_capacity=$coolDown resume_capacity=$resume pause_capacity=$pause"

    override fun getUpdateAccTemperatureCommand(coolDownTemperature: Int, temperatureMax: Int, wait: Int, shutdownTemperature: Int): String = "/dev/.vr25/acc/acca -s cooldown_temp=${coolDownTemperature} max_temp=${temperatureMax} resume_temp=$wait shutdown_temp=$shutdownTemperature"

    override fun getUpdateAccVoltControlCommand(voltFile: String?, voltMax: Int?): String = "/dev/.vr25/acc/acca --set --voltage ${voltMax?.toString() ?: "-"}"

    override fun getUpdateAccCurrentMaxCommand(currMax: Int?): String = "/dev/.vr25/acc/acca --set --current ${currMax?.toString() ?: "-"}"

    override fun getUpdateAccOnBootExitCommand(enabled: Boolean): String = "" //Not supported

    // These values are free-text entered by the user and get embedded inside the
    // double-quoted argument of `acca -s "key=value"`. Escape the four characters
    // that are special inside double quotes (\ " ` $) so a value containing a quote,
    // backtick or $(...) can't break the quoting or inject a shell command.
    private fun shellEscapeForDoubleQuotes(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("$", "\\$")

    override fun getUpdateAccOnBootCommand(command: String?): String = "/dev/.vr25/acc/acca -s \"apply_on_boot=${shellEscapeForDoubleQuotes(command.orEmpty())}\""


    override fun getUpdateAccOnPluggedCommand(command: String?) : String = "/dev/.vr25/acc/acca -s \"apply_on_plug=${shellEscapeForDoubleQuotes(command.orEmpty())}\""

    override fun getUpdateAccChargingSwitchCommand(switch: String?, automaticSwitchingEnabled: Boolean) : String {
        return if(switch != null) {
            // Always append the " --" manual-lock marker regardless of the caller's
            // automaticSwitchingEnabled flag. The auto-cycle path has an enforcement gap
            // (during cycle_switches_off the daemon briefly un-cuts each candidate to
            // test it); pinning manually closes that gap. Apply&Lock and the Add-new
            // dialog both already live-test before writing, so locking is safe here.
            "/dev/.vr25/acc/acca -s \"charging_switch=${shellEscapeForDoubleQuotes(switch)} --\""
        } else {
            "/dev/.vr25/acc/acca -s \"charging_switch=\""
        }
    }

    override fun getUpgradeCommand(version: String) = "/dev/.vr25/acc/acca --upgrade $version"

    // Off must send ACC's tri-state "no" (actively prefer clean on/off switches), not
    // "false" (no preference) — with "false", auto-select can still grab a flicker-prone
    // idle switch on Pixel/Tensor and the limit cycles on/off.
    override fun getUpdatePrioritizeBatteryIdleModeCommand(enabled: Boolean): String = "/dev/.vr25/acc/acca --set prioritize_batt_idle_mode=${if (enabled) "true" else "no"}"

    override fun getAddChargingSwitchCommand(switch: String): String = getUpdateAccChargingSwitchCommand(switch, false)
}