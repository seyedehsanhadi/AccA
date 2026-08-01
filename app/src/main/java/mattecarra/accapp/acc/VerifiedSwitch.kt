package mattecarra.accapp.acc

import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell

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

            val kv = HashMap<String, String>()
            for (line in res.out) {
                val i = line.indexOf('=')
                if (i > 0) kv[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }

            // Precondition stop (battery/thermal): written atomically with ok=0 and a reason. Check it
            // BEFORE the ok=1 sentinel so the user sees WHY it stopped instead of a generic "no switch".
            if (kv["result"] == "precondition") return Precondition(kv["reason"].orEmpty())

            // Sentinel + schema: a complete write ends with ok=1; reject a truncated/partial file.
            if (kv["ok"] != "1" || kv["schema"] != "1") return None
            if (kv["result"] == "no-switch") return NoSwitch

            val sw = kv["charging_switch"]?.takeIf { it.isNotBlank() } ?: return None
            val device = kv["device"].orEmpty()
            val soc = kv["soc"].orEmpty()

            // Fingerprint gate: the artifact must be for THIS exact device (device + soc).
            // If either side is unknown we don't hard-fail on it, but a positive mismatch is fatal.
            val liveDevice = getprop("ro.product.device")
            val liveSoc = getprop("ro.board.platform")
            if (device.isNotEmpty() && liveDevice.isNotEmpty() && device != liveDevice) return DeviceMismatch
            if (soc.isNotEmpty() && liveSoc.isNotEmpty() && soc != liveSoc) return DeviceMismatch

            // Path-exists gate: EVERY node of the switch must really exist on THIS device -- a
            // grouped multi-path spec with one vanished path must not be offered for a direct pin.
            val paths = sw.trim().split(' ').filter { it.startsWith("/") }
            if (paths.isNotEmpty()) {
                val check = paths.joinToString(" && ") { "[ -e \"$it\" ]" }
                if (!Shell.su(check).exec().isSuccess) return None
            }

            val klass = kv["class"].orEmpty()
            val conf = kv["conf"].orEmpty()
            val alts = parseAlts(kv)
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
                val sw = kv["alt${i}_switch"]?.takeIf { it.isNotBlank() } ?: break
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
            klass.equals("level", true) -> if (conf == "needs-test") ApplyMode.PIN_DIRECT else ApplyMode.LEVEL_RERUN
            else                        -> ApplyMode.LIVE_TEST
        }
    }

    /**
     * PIN_DIRECT  - proof already in hand (conf=verified, or an engage-proven level cap that is only
     *               slow to re-arm): lock straight away, no acc -t, no "connect charger".
     * LEVEL_RERUN - a %-cap whose enforcement was NOT proven (unconfirmed / pump). acc -t can only
     *               ever FALSE-fail it from below the cap, so ask for a re-run at a lower % instead.
     * LIVE_TEST   - a cut / bypass / drain switch acc -t CAN judge (status flips): run it.
     */
    enum class ApplyMode { PIN_DIRECT, LEVEL_RERUN, LIVE_TEST }
}
