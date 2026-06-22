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
    /** Freshly verified on THIS device. Safe to offer one-tap (still live-tested before the pin). */
    data class Verified(
        val switch: String, val klass: String, val conf: String, val device: String, val soc: String
    ) : VerifiedSwitch()

    /** Pump / low-confidence hold. Offer only as a suggestion; MUST live-test, never auto-pin. */
    data class NeedsTest(
        val switch: String, val klass: String, val conf: String, val device: String, val soc: String
    ) : VerifiedSwitch()

    /** Artifact is present but was produced on a different device — ignore it. */
    object DeviceMismatch : VerifiedSwitch()

    /** The tester ran but found no pinnable switch — make NO change to the user's config. */
    object NoSwitch : VerifiedSwitch()

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
            return if (conf == "verified") Verified(sw, klass, conf, device, soc)
            else NeedsTest(sw, klass, conf, device, soc)
        }

        @WorkerThread
        private fun getprop(name: String): String =
            Shell.su("getprop $name").exec().out.firstOrNull()?.trim().orEmpty()
    }
}
