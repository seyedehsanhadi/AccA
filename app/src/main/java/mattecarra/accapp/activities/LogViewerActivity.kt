package mattecarra.accapp.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.databinding.ActivityLogViewerBinding
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedAppActivity
import java.io.File

/**
 * Diagnostics screen.
 *
 * The previous version streamed `acc -L` (ACC's raw `set -x` shell execution
 * trace) line-by-line into a RecyclerView where each line had to be copied on
 * its own. That output is debug noise and effectively un-shareable.
 *
 * This version gathers a single, condensed, human-readable report once (on a
 * background thread, no live polling -> no battery/CPU cost) and shows it in a
 * selectable text view with Copy / Share / Refresh. The heavy full bundle
 * (dmesg, logcat, config, switch maps) stays available via the menu for deep
 * bug reports.
 */
class LogViewerActivity : ScopedAppActivity()
{
    private lateinit var binding: ActivityLogViewerBinding
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?)
    {
        LogExt().d(javaClass.simpleName, "onCreate()")
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.logToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_log_view)

        binding.logButtonCopy.setOnClickListener { copyToClipboard() }
        binding.logButtonShare.setOnClickListener { shareReport() }
        binding.logButtonRefresh.setOnClickListener { loadReport() }

        loadReport()
    }

    private fun setBusy(busy: Boolean)
    {
        binding.logProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.logButtonCopy.isEnabled = !busy
        binding.logButtonShare.isEnabled = !busy
        binding.logButtonRefresh.isEnabled = !busy
    }

    private fun loadReport()
    {
        setBusy(true)
        binding.logReportText.text = getString(R.string.log_gathering)
        launch {
            val text = withContext(Dispatchers.IO) { gatherReport() }
            report = text
            binding.logReportText.text = text
            setBusy(false)
        }
    }

    /**
     * Deep diagnostic: runs the bundled comprehensive acc-diag (device + the FULL charge-control node
     * inventory, so unknown phones are covered, + the daemon flight recorder + a computed failure
     * verdict), observe-only, and shows it here so the existing Copy / Share buttons work on it. The
     * script also auto-saves a copy to Downloads. This is the bundle a user sends us to get supported.
     */
    private fun runDeepDiag()
    {
        setBusy(true)
        binding.logReportText.text = getString(R.string.deep_diagnostic_running)
        launch {
            val text = withContext(Dispatchers.IO) { gatherDeepDiag() }
            report = text
            binding.logReportText.text = text
            setBusy(false)
            if (text.contains("schema=diag") && text.contains("ok=1"))
                Toast.makeText(this@LogViewerActivity, R.string.diag_saved_downloads, Toast.LENGTH_LONG).show()
        }
    }

    private fun gatherDeepDiag(): String
    {
        if (!Shell.rootAccess())
            return "root: NOT GRANTED\n\nThe deep diagnostic needs root to read ACC's data.\n"
        return try {
            val appFile = File(filesDir, "acc-diag.sh")
            assets.open("acc-diag.sh").use { input -> appFile.outputStream().use { output -> input.copyTo(output) } }
            Shell.su("cp ${appFile.absolutePath} /data/local/tmp/acc-diag.sh && chmod 0755 /data/local/tmp/acc-diag.sh").exec()
            Shell.su("sh /data/local/tmp/acc-diag.sh >/dev/null 2>&1").exec()
            val out = Shell.su("cat \$(ls -t /data/local/tmp/acc-diag-*.txt 2>/dev/null | head -1)").exec().out.joinToString("\n")
            if (out.isBlank()) "Deep diagnostic produced no output (is ACC installed?)." else out
        } catch (e: Exception) {
            LogExt().e(javaClass.simpleName, "acc-diag failed: $e")
            "Deep diagnostic failed: ${e.message}\n"
        }
    }

    /**
     * One root invocation, one shot. Pulls only the readable, useful sections
     * (no xtrace). Header is built in Kotlin so device/app facts are always
     * present even if root or ACC is unavailable.
     */
    private fun gatherReport(): String
    {
        val acca = "/dev/.vr25/acc/acca"
        val sb = StringBuilder()

        sb.append("# AccA diagnostics\n")
        sb.append("app          : ").append(appVersion()).append('\n')
        sb.append("device       : ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        sb.append("android      : ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("build        : ").append(Build.DISPLAY).append('\n')

        if (!Shell.rootAccess()) {
            sb.append("\nroot         : NOT GRANTED\n")
            sb.append("\nACC data is unavailable without root.\n")
            return sb.toString()
        }
        sb.append("root         : ok\n")

        // Single shell call emits all sections with clear separators.
        val script = """
            echo "@@VERSION"; $acca -v 2>/dev/null
            echo "@@KERNEL";  uname -a 2>/dev/null
            echo "@@DAEMON";  ( $acca -D >/dev/null 2>&1 && echo "running" || echo "stopped" )
            echo "@@BATTERY"; $acca -i 2>/dev/null
            echo "@@CONFIG";  $acca -sp 2>/dev/null
            echo "@@SWITCH";  $acca -s s: 2>/dev/null
            echo "@@SWITCHTEST"; cat /data/adb/vr25/acc-data/logs/switch-test.log 2>/dev/null; printf '\n-- daemon-confirmed working switches --\n'; cat /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null
            echo "@@LOGTAIL"; tail -n 40 /dev/.vr25/acc/accd-*.log 2>/dev/null | sed -E 's/^[0-9]+:[[:space:]]*//'
            echo "@@END"
        """.trimIndent()

        val out = Shell.su(script).exec().out.joinToString("\n")

        sb.append(section(out, "@@VERSION", "@@KERNEL", "ACC version"))
        sb.append(section(out, "@@KERNEL", "@@DAEMON", "Kernel"))
        sb.append(section(out, "@@DAEMON", "@@BATTERY", "Daemon"))
        sb.append(section(out, "@@BATTERY", "@@CONFIG", "Battery"))
        sb.append(section(out, "@@CONFIG", "@@SWITCH", "Config"))
        sb.append(section(out, "@@SWITCH", "@@SWITCHTEST", "Charging switches (available)"))
        sb.append(section(out, "@@SWITCHTEST", "@@LOGTAIL", "Switch test results (which method works on this phone)"))
        sb.append(section(out, "@@LOGTAIL", "@@END", "Daemon log (recent)"))

        return sb.toString().trimEnd() + "\n"
    }

    /** Extract the text between two markers and render it under a heading. */
    private fun section(all: String, start: String, end: String, title: String): String
    {
        val s = all.indexOf(start)
        if (s < 0) return ""
        val from = s + start.length
        val e = all.indexOf(end, from)
        val body = (if (e < 0) all.substring(from) else all.substring(from, e)).trim()
        if (body.isEmpty()) return "\n## $title\n(none)\n"
        return "\n## $title\n$body\n"
    }

    private fun appVersion(): String =
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "AccA ${pi.versionName} (${pi.versionCode})"
        } catch (e: Exception) { "AccA" }

    private fun copyToClipboard()
    {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AccA diagnostics", report))
        Toast.makeText(this, R.string.text_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport()
    {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_report_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share)))
    }

    private fun exportFullBundle()
    {
        setBusy(true)
        Toast.makeText(this, R.string.log_gathering, Toast.LENGTH_SHORT).show()
        launch {
            val path = withContext(Dispatchers.IO) {
                if (!Shell.rootAccess()) null
                else Shell.su("/dev/.vr25/acc/acca -l --export 2>/dev/null")
                    .exec().out.lastOrNull { it.trim().startsWith("/") }?.trim()
            }
            setBusy(false)
            if (path != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log path", path))
                Toast.makeText(this@LogViewerActivity, path, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@LogViewerActivity, R.string.log_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Charge-activity capture. A one-shot snapshot can't reveal a 1-2s charge
     * flicker, so this samples `acca -i` once per second for ~20s and records
     * level / status / current / voltage per second, then classifies the active
     * charging switch (level-type vs clean on/off) and flags an on/off
     * oscillation near the pause cap. User-initiated and time-bounded, so there
     * is no ongoing battery cost; it reads only, never changes charging.
     */
    private fun captureCharging()
    {
        setBusy(true)
        binding.logReportText.text = getString(R.string.log_capturing)
        launch {
            val text = withContext(Dispatchers.IO) { runCapture(samples = 20) }
            report = text
            binding.logReportText.text = text
            setBusy(false)
        }
    }

    private suspend fun runCapture(samples: Int): String
    {
        val acca = "/dev/.vr25/acc/acca"
        val sb = StringBuilder()
        sb.append("# AccA charge capture\n")
        sb.append("app          : ").append(appVersion()).append('\n')
        sb.append("device       : ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")

        if (!Shell.rootAccess()) {
            sb.append("\nroot         : NOT GRANTED\n")
            return sb.toString()
        }

        // Active switch + config band, captured once up front.
        val head = Shell.su(
            "echo @@SW; $acca -sp charging_switch 2>/dev/null; " +
            "echo @@CAP; $acca -sp capacity 2>/dev/null"
        ).exec().out.joinToString("\n")
        val swLine = sectionBody(head, "@@SW", "@@CAP")
        val capLine = sectionBody(head, "@@CAP", null)
        val switchVal = Regex("""charging_switch=(.*)""").find(swLine)
            ?.groupValues?.get(1)?.trim()?.trim('"')?.ifBlank { null }
        val switchType = classifySwitch(switchVal)

        sb.append("switch       : ").append(switchVal ?: "(automatic)").append('\n')
        sb.append("switch_type  : ").append(switchType).append('\n')
        if (capLine.isNotBlank()) sb.append("capacity     : ").append(capLine.trim()).append('\n')
        sb.append("\n## Samples (1/sec)\n")
        sb.append("t   level  status        current   voltage\n")

        var statusChanges = 0
        var prevStatus: String? = null
        var sawCharging = false
        var sawNotCharging = false

        for (t in 0 until samples) {
            val info = Shell.su("$acca -i 2>/dev/null").exec().out.joinToString("\n")
            val level = grab(info, """level\s+(\d+)""") ?: grab(info, """CAPACITY=(\d+)""") ?: "?"
            val status = (grab(info, """status\s+(\S+)""") ?: grab(info, """STATUS=(\S+)""") ?: "?")
            val curr = grab(info, """current_now\s+(\S+)""") ?: grab(info, """CURRENT_NOW=(\S+)""") ?: "?"
            val volt = grab(info, """voltage_now\s+(\S+)""") ?: grab(info, """VOLTAGE_NOW=(\S+)""") ?: "?"

            sb.append(String.format("%-3d %-6s %-13s %-9s %s\n", t, level, status, curr, volt))

            val chg = status.contains("Charging", true) && !status.contains("Not", true)
            if (chg) sawCharging = true else if (status != "?") sawNotCharging = true
            if (prevStatus != null && prevStatus != status) statusChanges++
            prevStatus = status

            if (t < samples - 1) delay(1000)
        }

        sb.append("\n## Verdict\n")
        val flicker = statusChanges >= 4 && sawCharging && sawNotCharging
        when {
            flicker && switchType == "level" ->
                sb.append("SWITCH FIGHT (flicker). Status changed ").append(statusChanges)
                    .append(" times in ").append(samples).append("s while a LEVEL-type switch is active.\n")
                    .append("The level switch (").append(switchVal)
                    .append(") is being re-armed by the charger firmware. Pin a clean on/off switch instead, ")
                    .append("or flash an ACC build with the switch stability gate.\n")
            flicker ->
                sb.append("Charging oscillated ").append(statusChanges)
                    .append(" times in ").append(samples).append("s. Possible switch fight; capture the active switch and test alternatives.\n")
            sawCharging && !sawNotCharging ->
                sb.append("Steady charging, no pause seen in ").append(samples).append("s (battery likely below the stop level).\n")
            !sawCharging && sawNotCharging ->
                sb.append("Steady paused/idle, no flicker — the limit is holding.\n")
            else ->
                sb.append("No clear pattern in ").append(samples).append("s.\n")
        }

        return sb.toString().trimEnd() + "\n"
    }

    /** Classify a switch value: a percent-like off value (e.g. "100 5") is a
     *  level/throttle node; a discrete pair (e.g. "0 1") is a clean on/off. */
    private fun classifySwitch(sw: String?): String
    {
        if (sw.isNullOrBlank()) return "automatic"
        val low = sw.lowercase()
        if (low.contains("charge_stop_level") || low.contains("siop_level") ||
            low.contains("temp_level") || low.contains("control_limit") ||
            Regex("""\b100\b""").containsMatchIn(sw)) return "level"
        return "clean on/off"
    }

    private fun grab(text: String, pattern: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

    private fun sectionBody(all: String, start: String, end: String?): String
    {
        val s = all.indexOf(start)
        if (s < 0) return ""
        val from = s + start.length
        val e = if (end != null) all.indexOf(end, from) else -1
        return (if (e < 0) all.substring(from) else all.substring(from, e)).trim()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean
    {
        menuInflater.inflate(R.menu.log_viewer_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        when (item.itemId)
        {
            android.R.id.home -> { finish(); return true }
            R.id.menu_deep_diag -> { runDeepDiag(); return true }
            R.id.menu_capture -> { captureCharging(); return true }
            R.id.menu_export_bundle -> { exportFullBundle(); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
