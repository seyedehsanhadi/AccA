package mattecarra.accapp.acc

import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import mattecarra.accapp.acc._interface.AccInterface

/**
 * Reads and SAFETY-GATES the verified-switch artifact written by the acc-compat tester
 * (v5.3+) at /data/local/tmp/acc-compat-verified.
 *
 * The tester proves a working charging switch on the phone it ran on; this gate makes sure
 * AccA never pins a stale / wrong-device / truncated / garbage switch (the charging-safety
 * P0 from the rc3 FMEA). It is a PURE read + validate step: it never writes anything. The
 * caller is responsible for the live `acca -t` self-test and the actual pin (so the test/pin
 * flow stays in one place — AccHandler — and this stays a small, testable module).
 *
 * Artifact contract (one key=value per line, written atomically by the tester):
 *   schema=1
 *   charging_switch=<path> <on> <off>      (the pinnable spec, or absent)
 *   class=bypass|cut|drain|level|throttle
 *   conf=verified|needs-test|unconfirmed|pump-needs-long-test
 *   device=<ro.product.device>  soc=<ro.board.platform>
 *   result=no-switch                       (when nothing pinnable was found)
 *   ok=1                                   (sentinel — MUST be the last line; proves a complete write)
 */
sealed class VerifiedSwitch {
    /** One working switch the tester reported -- the recommended pick OR an alternative (for the list). */
    data class Alt(
        val switch: String, val klass: String, val conf: String,
        val resume: String, val latch: Boolean, val note: String, val stability: String = ""
    )

    /** Freshly verified on THIS device. Safe to offer one-tap (still live-tested before the pin). */
    data class Verified(
        val switch: String, val klass: String, val conf: String, val device: String, val soc: String,
        val alts: List<Alt> = emptyList(), val accCurrentSwitch: String? = null, val accCurrentClass: String? = null,
        val recStability: String = "", val recLatch: Boolean = false
    ) : VerifiedSwitch()

    /** Pump / low-confidence hold. Offer only as a suggestion; MUST live-test, never auto-pin. */
    data class NeedsTest(
        val switch: String, val klass: String, val conf: String, val device: String, val soc: String,
        val alts: List<Alt> = emptyList(), val accCurrentSwitch: String? = null, val accCurrentClass: String? = null,
        val recStability: String = "", val recLatch: Boolean = false
    ) : VerifiedSwitch()

    /** Artifact is present but was produced on a different device — ignore it. */
    object DeviceMismatch : VerifiedSwitch()

    /** The tester ran but found no pinnable switch — make NO change to the user's config. */
    object NoSwitch : VerifiedSwitch()

    /** Precondition not met (battery too high/low, or too hot): the tester stopped before changing
     *  anything. Show the reason so the user can fix it and retry — this is NOT a "no switch" result. */
    data class Precondition(val reason: String) : VerifiedSwitch()

    /** No artifact, unreadable, incomplete, or the node no longer exists — nothing to offer. */
    object None : VerifiedSwitch()

    companion object {
        private const val ARTIFACT = "/data/local/tmp/acc-compat-verified"

        @WorkerThread
        fun detect(): VerifiedSwitch {
            val res = Shell.su("cat $ARTIFACT 2>/dev/null").exec()
            if (!res.isSuccess || res.out.isEmpty()) return None

            val result = parseArtifact(res.out, getprop("ro.product.device"), getprop("ro.board.platform"))
            val sw = when (result) {
                is Verified -> result.switch
                is NeedsTest -> result.switch
                else -> return result
            }
            return if (pathsExist(sw)) result else None
        }

        internal fun parseArtifact(lines: List<String>, liveDevice: String, liveSoc: String): VerifiedSwitch {
            val kv = HashMap<String, String>()
            for (line in lines) {
                val i = line.indexOf('=')
                if (i <= 0) return None
                val key = line.substring(0, i).trim()
                if (kv.put(key, line.substring(i + 1).trim()) != null) return None
            }
            if (kv["schema"] != "1") return None
            val precondition = kv["result"] == "precondition"
            if (lines.lastOrNull()?.trim() != if (precondition) "ok=0" else "ok=1") return None
            val device = kv["device"].orEmpty()
            val soc = kv["soc"].orEmpty()
            if (device.isNotEmpty() && liveDevice.isNotEmpty() && device != liveDevice) return DeviceMismatch
            if (soc.isNotEmpty() && liveSoc.isNotEmpty() && soc != liveSoc) return DeviceMismatch
            if (precondition) return Precondition(kv["reason"].orEmpty())
            if (kv["result"] == "no-switch") return NoSwitch
            val sw = kv["charging_switch"]?.takeIf { switchPaths(it) != null } ?: return None

            val klass = kv["class"].orEmpty()
            val conf = kv["conf"].orEmpty().let {
                if (it == "verified" && (device.isBlank() || liveDevice.isBlank())) "needs-test" else it
            }
            val alts = parseAlts(kv).map { if ((device.isBlank() || liveDevice.isBlank()) && it.conf == "verified") it.copy(conf = "needs-test") else it }
            val accSw = kv["acc_current_switch"]?.takeIf { it.isNotBlank() }
            val accCls = kv["acc_current_class"]?.takeIf { it.isNotBlank() }
            val recStab = kv["rec_stability"].orEmpty()
            val recLat = kv["rec_latch"] == "yes"
            return if (conf == "verified") Verified(sw, klass, conf, device, soc, alts, accSw, accCls, recStab, recLat)
            else NeedsTest(sw, klass, conf, device, soc, alts, accSw, accCls, recStab, recLat)
        }

        /** Parse the additive alt1..altN_* rows the tester writes (every OTHER working switch,
         *  reliability-ranked). Probe alt{i}_switch until the first gap -- do NOT trust alt_count,
         *  so a missing/short count can never silently drop present rows. */
        private fun parseAlts(kv: Map<String, String>): List<Alt> {
            val out = ArrayList<Alt>()
            var i = 1
            while (i <= 256) {
                val sw = kv["alt${i}_switch"] ?: break
                if (switchPaths(sw) == null) { i++; continue }
                out.add(
                    Alt(
                        sw, kv["alt${i}_class"].orEmpty(), kv["alt${i}_conf"].orEmpty(),
                        kv["alt${i}_resume"].orEmpty(), kv["alt${i}_latch"] == "yes", kv["alt${i}_note"].orEmpty(),
                        kv["alt${i}_stability"].orEmpty()
                    )
                )
                i++
            }
            return out
        }

        /** ACC specs are path/on/off triples; relative nodes live under power_supply. */
        internal fun switchPaths(spec: String): List<String>? {
            val fields = spec.trim().split(Regex("\\s+"))
            if (fields.size < 3 || fields.size % 3 != 0) return null
            return fields.chunked(3).map { triple ->
                val node = triple[0]
                if (node.split('/').any { it == ".." } || node == "/") return null
                if (node.startsWith('/')) node else "/sys/class/power_supply/$node"
            }
        }

        @WorkerThread
        fun pathsExist(spec: String): Boolean {
            val paths = switchPaths(spec) ?: return false
            val check = paths.joinToString(" && ") { "[ -e '" + it.replace("'", "'\\''") + "' ]" }
            return try { Shell.su(check).exec().isSuccess } catch (_: Exception) { false }
        }

        /** A saved pin is not success until the selected handler confirms a running daemon. */
        suspend fun pinAndRestart(acc: AccInterface, spec: String): Boolean {
            if (!acc.updateAccChargingSwitch(spec, false)) return false
            repeat(2) {
                val restarted = acc.accRestartDaemon()
                delay(2500)
                if (restarted && acc.isAccdRunning()) return true
            }
            return false
        }

        @WorkerThread
        private fun getprop(name: String): String =
            Shell.su("getprop $name").exec().out.firstOrNull()?.trim().orEmpty()

        /**
         * How a picked switch should be applied, from its class + confidence. Single source of truth
         * for the "does acc -t run?" decision, shared by the editor card and the switch finder.
         *
         * A firmware %-limit (class=level) is proven by the tester's own engage+hold step, and ACC's
         * binary `acca -t` writes the switch's OFF value = the pause %; when the battery sits BELOW
         * that % charging correctly keeps going, so `acca -t` returns a FALSE "Switch doesn't work"
         * for EVERY level switch tested below its cap (the Pixel 9a report). So a level switch must
         * never touch acc -t: pin it if enforcement was proven, otherwise ask for a lower-% re-run.
         */
        fun applyMode(klass: String, conf: String): ApplyMode = when {
            conf == "verified"          -> ApplyMode.PIN_DIRECT
            klass.equals("level", true) -> ApplyMode.LEVEL_RERUN
            else                        -> ApplyMode.LIVE_TEST
        }
    }

    /**
     * PIN_DIRECT  - proof already in hand (conf=verified):
     *               lock straight away, no acc -t, no "connect charger".
     * LEVEL_RERUN - a %-cap without verified confidence (needs-test / unconfirmed / pump). acc -t can only
     *               ever FALSE-fail it from below the cap, so ask for a re-run at a lower % instead.
     * LIVE_TEST   - a cut / bypass / drain switch acc -t CAN judge (status flips): run it.
     */
    enum class ApplyMode { PIN_DIRECT, LEVEL_RERUN, LIVE_TEST }
}
