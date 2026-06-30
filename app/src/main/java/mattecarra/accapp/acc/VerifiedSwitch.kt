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
 *   conf=verified|pump-needs-long-test
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

            // Path-exists gate: the switch's first node must really exist on THIS device.
            val firstPath = sw.trim().substringBefore(' ')
            if (firstPath.startsWith("/")) {
                if (!Shell.su("[ -e \"$firstPath\" ]").exec().isSuccess) return None
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
    }
}
