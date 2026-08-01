package mattecarra.accapp.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.viewModels
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
import com.afollestad.materialdialogs.MaterialDialog
import mattecarra.accapp.utils.Breadcrumbs
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedAppActivity
import mattecarra.accapp.viewmodel.ChargeCaptureViewModel
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
    private val capVM: ChargeCaptureViewModel by viewModels()

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

        // Live charge capture streams here and survives rotation/screen-off (it lives in the ViewModel).
        // Re-observing on recreate restores the growing text and the still-running capture.
        capVM.state().observe(this) { s ->
            if (s.text.isNotEmpty()) {
                binding.logReportText.text = s.text
                report = s.text
                binding.logReportText.post { binding.logScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }
        if (capVM.isRunning) setBusy(false) else loadReport()
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

    // The app used to carry its OWN bundled acc-diag.sh asset and run that. It was left
    // unreachable when the menu moved to showReportDialog(), so the APK was shipping a second,
    // older collector that could never run and could silently drift from the module's canonical
    // one. Removed: there is exactly ONE diagnostic now, the module's diag-collect.sh reached
    // through `acc --diag`, so app users and CLI users always send the identical bundle.

    /**
     * THE ONE diagnostic. Runs the module's canonical passive collector (`acc --diag` -> diag-collect.sh),
     * which gathers EVERYTHING into a single .tgz -- identity, config, live state, ACC's own logs, plus
     * Android's own logcat / pstore-panic / crash / ANR / persistent reboot-archive records + a manifest
     * of what was captured -- then shares that one file. Nothing runs in the background; this executes
     * only on tap. Same collector the CLI `acc --diag` uses, so app users and CLI users send the identical
     * bundle. The file is copied into app-private storage so it can be shared via FileProvider.
     */
    /** Ask which report to collect before running: Quick (core, default) or Full (--full, everything). */
    private fun showReportDialog()
    {
        MaterialDialog(this).show {
            title(R.string.report_dialog_title)
            message(R.string.report_dialog_message)
            positiveButton(R.string.report_quick) { collectAndShare(false) }
            neutralButton(R.string.report_full) { collectAndShare(true) }
        }
    }

    private fun collectAndShare(full: Boolean)
    {
        setBusy(true)
        // capture the app-side event trail so the (root) collector bundles it
        Breadcrumbs.add("diagnostics: collect & share (" + (if (full) "full" else "quick") + ")")
        Breadcrumbs.flush(filesDir)
        binding.logReportText.text = getString(R.string.deep_diagnostic_running)
        launch {
            val file = withContext(Dispatchers.IO) {
                if (!Shell.rootAccess()) return@withContext null
                // canonical collector (Quick=core; Full=--full); fall back to the module path if `acc` is not on PATH (e.g. Tensor)
                val arg = if (full) " --full" else ""
                val out = Shell.su(
                    "b=\$(acc --diag$arg 2>&1 | grep -m1 '^bundle:'); " +
                    "[ -z \"\$b\" ] && [ -f /data/adb/vr25/acc/diag-collect.sh ] && b=\$(sh /data/adb/vr25/acc/diag-collect.sh$arg 2>&1 | grep -m1 '^bundle:'); " +
                    "echo \"\$b\""
                ).exec().out.joinToString("\n")
                // accept any extension: the collector picks bzip2 (.tar.bz2) when present, else gzip (.tgz)
                val src = Regex("""bundle:\s*(\S+)""").find(out)?.groupValues?.get(1) ?: return@withContext null
                val dir = File(filesDir, "logs").apply { mkdirs() }
                val dest = File(dir, src.substringAfterLast('/'))
                Shell.su("cp -f '$src' '${dest.absolutePath}' && chmod 0644 '${dest.absolutePath}'").exec()
                if (dest.exists() && dest.length() > 0) dest else null
            }
            setBusy(false)
            if (file != null) {
                val uri = FileProvider.getUriForFile(this@LogViewerActivity, "$packageName.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "ACC diagnostic (${file.name})")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                binding.logReportText.text = getString(R.string.report_ready_body)
                // clear "ready" dialog with a big Share button, so a non-technical user never has to hunt in Downloads
                MaterialDialog(this@LogViewerActivity).show {
                    title(R.string.report_ready_title)
                    message(R.string.report_ready_message)
                    positiveButton(R.string.report_share) { startActivity(Intent.createChooser(shareIntent, getString(R.string.log_share))) }
                    negativeButton(R.string.report_done)
                }
            } else {
                binding.logReportText.text = getString(R.string.log_export_failed)
                Toast.makeText(this@LogViewerActivity, R.string.log_export_failed, Toast.LENGTH_SHORT).show()
            }
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

        // Clean, human-readable summary only. The full detail (every candidate switch, the raw config,
        // the daemon trace) lives in the shared diagnostic bundle, NOT on this screen -- the old
        // "Charging switches (available)" list read as "these are YOUR switches" and confused users.
        val script = """
            echo "@@VERSION"; $acca -v 2>/dev/null
            echo "@@DAEMON";  ( $acca -D >/dev/null 2>&1 && echo "running" || echo "stopped" )
            echo "@@KERNEL";  uname -r 2>/dev/null
            echo "@@BATTERY"; $acca -i 2>/dev/null | grep -iE 'level|status|temp|current|voltage|charge_type|power'
            echo "@@CAP";     sed -n 's/^capacity=//p' /data/adb/vr25/acc-data/config.txt 2>/dev/null
            echo "@@TEMP";    sed -n 's/^temperature=//p' /data/adb/vr25/acc-data/config.txt 2>/dev/null
            echo "@@SWITCH";  sed -n 's/^chargingSwitch=//p' /data/adb/vr25/acc-data/config.txt 2>/dev/null
            echo "@@WORKING"; cat /data/adb/vr25/acc-data/logs/working-switches.log 2>/dev/null
            echo "@@END"
        """.trimIndent()

        val out = Shell.su(script).exec().out.joinToString("\n")

        sb.append(section(out, "@@VERSION", "@@DAEMON", "ACC version"))
        sb.append(section(out, "@@DAEMON", "@@KERNEL", "Daemon"))
        sb.append(section(out, "@@KERNEL", "@@BATTERY", "Kernel"))
        sb.append(section(out, "@@BATTERY", "@@CAP", "Charging now"))

        sb.append("\n## Your charge limit\n")
        sb.append(friendlyCap(between(out, "@@CAP", "@@TEMP"))).append('\n')
        val temp = between(out, "@@TEMP", "@@SWITCH")
        if (temp.isNotBlank()) sb.append("temperature limits (°C): ").append(temp).append('\n')

        val sw = between(out, "@@SWITCH", "@@WORKING")
        val working = between(out, "@@WORKING", "@@END")
        sb.append("\n## Charging switch\n")
        sb.append("active: ").append(if (sw.isBlank()) "(none set)" else sw).append('\n')
        sb.append("confirmed working here: ").append(if (working.isBlank()) "not tested yet — use Switch Finder" else working).append('\n')

        return sb.toString().trimEnd() + "\n"
    }

    /** Raw body between two markers (no heading). */
    private fun between(all: String, start: String, end: String): String {
        val s = all.indexOf(start); if (s < 0) return ""
        val from = s + start.length; val e = all.indexOf(end, from)
        return (if (e < 0) all.substring(from) else all.substring(from, e)).trim()
    }

    /** Turn the raw ACC capacity tuple into plain language; fall back to raw if the shape is unexpected. */
    private fun friendlyCap(raw: String): String {
        val p = raw.trim().replace(",", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            p.size >= 4 -> "stops charging at ${p[3]}%, resumes at ${p[2]}%  (raw: ${raw.trim()})"
            // ACC orders the tuple resume-before-pause, which is why the 4-field branch above
            // reads stop from p[3] and resume from p[2]. The short form follows the same order,
            // so stop is p[1]; reading it as p[0] printed the two numbers swapped.
            p.size == 2 -> "stops at ${p[1]}%, resumes at ${p[0]}%  (raw: ${raw.trim()})"
            raw.isBlank() -> "(not set)"
            else -> raw.trim()
        }
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
     * flicker, so this samples `acca -i` once per second for ~60s and records
     * level / status / current / voltage per second, then classifies the active
     * charging switch (level-type vs clean on/off) and flags an on/off
     * oscillation near the pause cap. User-initiated and time-bounded, so there
     * is no ongoing battery cost; it reads only, never changes charging.
     */
    // Toggle: first tap starts the live capture (streams into the ViewModel, survives rotation);
    // tapping again stops it early. The observer in onCreate renders the growing text.
    private fun captureCharging()
    {
        if (capVM.isRunning) capVM.stop() else capVM.start(60)
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
            R.id.menu_deep_diag -> { showReportDialog(); return true }
            R.id.menu_capture -> { captureCharging(); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
