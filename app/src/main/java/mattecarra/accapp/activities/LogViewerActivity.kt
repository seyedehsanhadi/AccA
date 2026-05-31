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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.databinding.ActivityLogViewerBinding
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedAppActivity

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
            echo "@@LOGTAIL"; tail -n 40 /dev/.vr25/acc/accd-*.log 2>/dev/null | sed -E 's/^[0-9]+:[[:space:]]*//'
            echo "@@END"
        """.trimIndent()

        val out = Shell.su(script).exec().out.joinToString("\n")

        sb.append(section(out, "@@VERSION", "@@KERNEL", "ACC version"))
        sb.append(section(out, "@@KERNEL", "@@DAEMON", "Kernel"))
        sb.append(section(out, "@@DAEMON", "@@BATTERY", "Daemon"))
        sb.append(section(out, "@@BATTERY", "@@CONFIG", "Battery"))
        sb.append(section(out, "@@CONFIG", "@@SWITCH", "Config"))
        sb.append(section(out, "@@SWITCH", "@@LOGTAIL", "Charging switches"))
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
            R.id.menu_export_bundle -> { exportFullBundle(); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
