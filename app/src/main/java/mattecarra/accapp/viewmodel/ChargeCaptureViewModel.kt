package mattecarra.accapp.viewmodel

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live per-second charge capture + fast-charger / charging-speed check.
 *
 * Runs in viewModelScope, so it SURVIVES screen rotation and screen-off -- the
 * Activity is recreated but this ViewModel (and its coroutine) is not; the new
 * Activity re-observes the growing text and the capture keeps going. Streams each
 * reading immediately (postValue) instead of dumping after N seconds.
 *
 * Two things at once, both read from raw sysfs each second (accurate, no wrapper):
 *  - CHARGER / SPEED: usb/real_type (adapter class), usb/voltage_now (VBUS: 5V vs
 *    9/12V), input current, usb/voltage_max, usb/pd_active -> the real input wattage
 *    (V x A) and a plain "is fast charging working" verdict. This is what to point
 *    people at when they complain their charging is slow.
 *  - BATTERY DYNAMICS: current_now (0 while Charging = stuck; sign flips = switch
 *    toggling), input_suspend (switch fight), status, temp -> catches the flicker /
 *    stuck / thermal faults a snapshot misses.
 * Read-only, user-started, time-bounded. Holds a PARTIAL wakelock ONLY while a capture
 * runs (so sampling continues with the screen off); it is time-boxed and released in the
 * finally block, so there is no background/idle battery cost.
 */
class ChargeCaptureViewModel(app: Application) : AndroidViewModel(app) {

    data class State(val text: String, val running: Boolean, val elapsed: Int, val total: Int)

    private val _state = MutableLiveData(State("", false, 0, 0))
    fun state(): LiveData<State> = _state
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null

    val isRunning: Boolean get() = job?.isActive == true

    private fun releaseWl() { try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wl = null }

    fun start(seconds: Int) {
        if (isRunning) return
        // partial wakelock so the per-second sampling keeps running with the SCREEN OFF -- which is also
        // how you see max charging speed (the display isn't drawing). Time-boxed + released in finally.
        try {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AccA:chargeCapture").also { it.setReferenceCounted(false); it.acquire((seconds + 15) * 1000L) }
        } catch (_: Exception) {}
        job = viewModelScope.launch(Dispatchers.IO) {
          try {
            val bat = "/sys/class/power_supply/battery"
            val usb = "/sys/class/power_supply/usb"
            val acca = "/dev/.vr25/acc/acca"
            if (!Shell.rootAccess()) { _state.postValue(State("root: NOT GRANTED — cannot capture", false, 0, seconds)); return@launch }

            // Ask the daemon how to read this device's current node before sampling it:
            // currentUnits is uA or mA and polarity is normal or inverted. Hardcoding uA
            // with an unsigned divide printed +117 mA on a Mi A3 that was discharging.
            val sense = Shell.su("$acca --state 2>/dev/null").exec().out.joinToString("")
            val curDiv = if (sense.contains("\"currentUnits\":\"mA\"")) 1L else 1000L
            // "unstable" (dual-path PMIC) means the raw sign follows the charge PATH and carries
            // no meaning; only "inverted" was handled, so those devices logged the wrong direction
            // for the whole capture. Capture the daemon's own words and let AccState's rule decide.
            val sensePolarity = Regex(""""polarity"\s*:\s*"([a-z]+)"""").find(sense)?.groupValues?.get(1) ?: "normal"
            val senseClass = Regex(""""measuredClass"\s*:\s*"([a-z]+)"""").find(sense)?.groupValues?.get(1) ?: ""
            val head = Shell.su("$acca -sp charging_switch 2>/dev/null; $acca -sp capacity 2>/dev/null").exec().out.joinToString("\n")
            val sw = Regex("""charging_switch=(.*)""").find(head)?.groupValues?.get(1)?.trim()?.trim('"')?.ifBlank { null } ?: "(automatic)"
            val cap = Regex("""capacity=(.*)""").find(head)?.groupValues?.get(1)?.trim() ?: ""
            // The on-disk key is maxChargingCurrent= (camelCase); the old snake_case sed
            // never matched, so mcc was always null and speedVerdict() never saw the cap.
            val mccRaw = Shell.su("sed -n 's/^maxChargingCurrent=//p' /data/adb/vr25/acc-data/config.txt 2>/dev/null").exec().out.joinToString("").trim()
            val mcc = Regex("""\(?\s*(\d+)""").find(mccRaw)?.groupValues?.get(1)?.ifBlank { null }

            val fixed = StringBuilder()
            fixed.append("# Live charge capture — 1 reading/sec for ${seconds}s\n")
            fixed.append("tip: turn the SCREEN OFF now — it keeps sampling and shows your MAX charging speed (no display drain). Copy/Share when done.\n")
            fixed.append("switch : $sw  [${classify(sw)}]\n")
            if (cap.isNotBlank()) fixed.append("limit  : $cap\n")

            val log = StringBuilder("\n## battery (1/sec)\n t  lvl  status        curr(mA)  susp temp\n")
            var changes = 0; var prev: String? = null; var sawChg = false; var sawNot = false; var stuck = 0; var maxT = 0
            var lastCharger = ""; var speedV = "..."

            for (t in 0 until seconds) {
                val t0 = SystemClock.elapsedRealtime()
                val line = Shell.su(
                    "echo \"\$(cat $bat/capacity 2>/dev/null)|\$(cat $bat/status 2>/dev/null)|\$(cat $bat/current_now 2>/dev/null)|\$(cat $bat/temp 2>/dev/null)|\$(cat $bat/input_suspend 2>/dev/null)|" +
                    "\$(cat $usb/online 2>/dev/null)|\$(cat $usb/real_type 2>/dev/null)|\$(cat $usb/type 2>/dev/null)|\$(cat $usb/voltage_now 2>/dev/null)|\$(cat $usb/input_current_now 2>/dev/null)|\$(cat $usb/current_now 2>/dev/null)|\$(cat $usb/voltage_max 2>/dev/null)|\$(cat $usb/pd_active 2>/dev/null)|\$(cat /sys/class/power_supply/ac/online 2>/dev/null)|\$(cat /sys/class/power_supply/wireless/online 2>/dev/null)\""
                ).exec().out.joinToString("").trim()
                val f = line.split("|")
                // battery
                val lvl = f.getOrNull(0)?.ifBlank { null } ?: "?"
                val st = f.getOrNull(1)?.ifBlank { null } ?: "?"
                val iRaw = f.getOrNull(2)?.toLongOrNull(); val tRaw = f.getOrNull(3)?.toIntOrNull()
                val susp = f.getOrNull(4)?.ifBlank { null } ?: "?"
                val mA = iRaw?.let {
                    mattecarra.accapp.models.AccState
                        .normaliseMilliAmps((it / curDiv).toFloat(), sensePolarity, senseClass, st).toLong()
                }; val tempC = tRaw?.div(10)
                log.append(String.format("%2d  %-4s %-13s %-9s %-4s %s\n", t, "$lvl%", st, mA?.toString() ?: "?", susp, tempC?.let { "${it}C" } ?: "?"))
                val chg = st.contains("Charging", true) && !st.contains("Not", true)
                if (chg) { sawChg = true; if (mA != null && mA in -30..30) stuck++ } else if (st != "?") sawNot = true
                if (prev != null && prev != st) changes++; prev = st
                if (tempC != null && tempC > maxT) maxT = tempC
                // charger / speed (latest)
                // usb/online alone reports 0 on a wireless charger, so a wireless capture said
                // "not charging" throughout. Any supply being online counts.
                val online = f.getOrNull(5) == "1" || f.getOrNull(13) == "1" || f.getOrNull(14) == "1"
                val realType = f.getOrNull(6)?.ifBlank { null } ?: f.getOrNull(7)?.ifBlank { null } ?: "?"
                val vbus = f.getOrNull(8)?.toLongOrNull(); val inA = f.getOrNull(9)?.toLongOrNull() ?: f.getOrNull(10)?.toLongOrNull()
                val vmax = f.getOrNull(11)?.toLongOrNull(); val pd = f.getOrNull(12) == "1"
                // The PACK side is normalised from what the daemon learned; the BUS side was left
                // on a hardcoded microvolt/microamp divide, so a kernel publishing usb/voltage_now
                // in millivolts printed ~0.007 V and a watts figure to match. Normalise by
                // magnitude instead, the same way BatteryInfo.getVoltageNow does: a charger bus
                // is physically 3-24 V and its current 0-10 A, so the scales never overlap.
                fun busVolts(raw: Long?): Double? = raw?.let {
                    when { it >= 100000L -> it / 1e6      // microvolts
                           it >= 100L    -> it / 1e3      // millivolts
                           else          -> it.toDouble() // already volts
                    } }
                // Current CANNOT be scaled by magnitude the way voltage can: 50 mA is 50000 uA,
                // and 50000 read as milliamps is 50 A. A kernel publishes the whole usb/* group in
                // ONE convention, so take the scale from the bus VOLTAGE (3-24 V is unambiguous)
                // and apply it to the current.
                val busDiv = when { (vbus ?: 0L) >= 100000L -> 1e6   // usb/* is micro-scale
                                    (vbus ?: 0L) >= 100L    -> 1e3   // usb/* is milli-scale
                                    else                    -> 1.0 } // already volts/amps
                fun busAmps(raw: Long?): Double? = raw?.let { it / busDiv }
                val vbusV = busVolts(vbus); val inAmp = busAmps(inA)
                val inW = if (vbusV != null && inAmp != null) vbusV * inAmp else null
                lastCharger = chargerLine(online, realType, vbusV, inAmp, inW, pd, busVolts(vmax))
                speedV = speedVerdict(online, realType, vbusV, inW, pd, busVolts(vmax), mcc)

                _state.postValue(State(fixed.toString() + "\n## charger / speed\n" + lastCharger + "\nverdict: " + speedV + "\n" + log.toString() + runningNote(t + 1, changes, stuck), true, t + 1, seconds))
                // hold a true ~1s cadence: the root read above already spent ~0.4s, so delay only the remainder,
                // otherwise 60 readings drift to ~85s wall-clock and it is no longer "1 reading/sec".
                if (t < seconds - 1) delay((1000L - (SystemClock.elapsedRealtime() - t0)).coerceIn(0L, 1000L))
            }
            val battVerdict = verdict(seconds, changes, sawChg, sawNot, stuck, maxT, classify(sw), sw)
            _state.postValue(State(fixed.toString() + "\n## charger / speed\n" + lastCharger + "\nverdict: " + speedV + "\n" + log.toString() + "\n## battery verdict\n" + battVerdict + "\n", false, seconds, seconds))
          } finally { releaseWl() }
        }
    }

    fun stop() { job?.cancel(); releaseWl(); _state.value?.let { _state.postValue(it.copy(running = false, text = it.text + "\n(stopped early)")) } }

    override fun onCleared() { job?.cancel(); releaseWl() }

    // ---- charger / charging-speed ----
    private fun adapter(realType: String?): String {
        val r = (realType ?: "").uppercase()
        return when {
            r.contains("PPS") -> "USB-PD PPS"; r.contains("PD") -> "USB-PD"
            r.contains("HVDCP_3") || r.contains("QC3") -> "QuickCharge 3"; r.contains("HVDCP") || r.contains("QC") -> "QuickCharge"
            r.contains("DCP") -> "wall charger (DCP)"; r.contains("CDP") -> "USB port (CDP)"
            r.contains("SDP") || r == "USB" -> "USB port (SDP)"
            r.isBlank() || r == "?" || r.contains("UNKNOWN") || r.contains("FLOAT") -> "unknown"
            else -> realType ?: "unknown"
        }
    }
    private fun chargerLine(online: Boolean, rt: String?, vbusV: Double?, inA: Double?, inW: Double?, pd: Boolean, vmaxV: Double?): String {
        if (!online) return "not charging (no charger online / input suspended)"
        return "adapter ${adapter(rt)}${if (pd) " (PD active)" else ""}, " +
            "in ${vbusV?.let { String.format("%.1f", it) } ?: "?"}V x ${inA?.let { String.format("%.2f", it) } ?: "?"}A = ${inW?.let { String.format("%.1f", it) } ?: "?"}W" +
            (vmaxV?.let { ", negotiated max ${String.format("%.0f", it)}V" } ?: "")
    }
    private fun speedVerdict(online: Boolean, rt: String?, vbusV: Double?, inW: Double?, pd: Boolean, vmaxV: Double?, mcc: String?): String {
        if (!online) return "not charging — plug the charger in and run again (if plugged, ACC may have paused at your limit)."
        val fastActive = (vbusV != null && vbusV >= 8.5) || pd
        val fastCapable = fastActive || (vmaxV != null && vmaxV >= 8.5) ||
            adapter(rt).let { it.contains("PD") || it.contains("Quick") }
        val w = inW?.let { String.format("%.0f", it) } ?: "?"
        val base = when {
            fastActive -> "FAST charging is WORKING — ~${w}W (${vbusV?.let { String.format("%.1f", it) }}V${if (pd) ", PD" else ""})."
            fastCapable -> "fast-capable adapter but only ~${vbusV?.let { String.format("%.1f", it) }}V negotiated (~${w}W) — likely a cable/port/handshake problem; try the OEM cable and another port."
            else -> "standard charging ~${w}W (adapter ${adapter(rt)}, 5V) — this is NOT a fast charger."
        }
        return base + if (mcc != null && mcc != "" && mcc != "0") "  NOTE: ACC caps current to ${mcc} — that is YOUR charge-control setting, not the charger." else ""
    }

    // ---- battery dynamics ----
    private fun runningNote(n: Int, changes: Int, stuck: Int): String {
        val flags = ArrayList<String>()
        if (changes >= 4) flags.add("status flapping x$changes")
        if (stuck >= 3) flags.add("current ~0 while charging (stuck?)")
        return "\n[live] ${n}s captured" + if (flags.isEmpty()) "" else " — " + flags.joinToString(", ")
    }
    private fun classify(sw: String?): String = when {
        sw == null || sw == "(automatic)" -> "automatic"
        sw.contains("current") || sw.contains("_max") || sw.contains("voltage") -> "level-type"
        else -> "on/off"
    }
    private fun verdict(s: Int, changes: Int, chg: Boolean, not: Boolean, stuck: Int, maxT: Int, type: String, sw: String?): String {
        val flicker = changes >= 4 && chg && not
        return when {
            stuck >= 3 -> "STUCK: current stayed ~0 while status=Charging for ${stuck}/${s}s — charge is not actually flowing (switch or hardware). switch=$sw."
            flicker && type == "level-type" -> "SWITCH FIGHT: status flipped ${changes}x in ${s}s on a level-type switch ($sw) — the charger firmware keeps re-arming it. Pin a clean on/off switch."
            flicker -> "OSCILLATION: status flipped ${changes}x in ${s}s — possible switch fight; test alternative switches."
            maxT >= 42 -> "THERMAL: battery reached ${maxT}C during capture — charging may be throttled by heat."
            chg && !not -> "steady charging, no pause in ${s}s (battery below the stop level)."
            !chg && not -> "steady paused/idle, no flicker — the limit is holding."
            else -> "no clear charge activity in ${s}s (unplugged?)."
        }
    }
}
