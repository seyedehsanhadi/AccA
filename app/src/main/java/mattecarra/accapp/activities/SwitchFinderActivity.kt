package mattecarra.accapp.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.list.listItems
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.acc.VerifiedSwitch
import mattecarra.accapp.databinding.ActivitySwitchFinderBinding
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ScopedAppActivity
import java.util.concurrent.Executor

/**
 * "Find my charging switch": extracts the bundled acc-compat tester to /data/local/tmp, runs it
 * as root with STREAMING stdout, and shows a live auto-scrolling log (the "what the device is
 * doing" view), a spinner, an elapsed-seconds counter and a friendly status header. On completion
 * it reads the verified-switch artifact via [VerifiedSwitch.detect] and offers an "Apply this
 * switch" button that runs the SAME live-test-then-pin path as the editor's Apply & Lock card.
 *
 * Everything heavy runs off the main thread; the shell job is captured so it can be cancelled if
 * the user taps Cancel or the activity is destroyed mid-run.
 */
class SwitchFinderActivity : ScopedAppActivity()
{
    private lateinit var binding: ActivitySwitchFinderBinding

    // The libsu job running the tester. Captured so onDestroy / Cancel can stop it.
    private var shellJob: Shell.Job? = null
    // The async submit handle, so we know when the run has finished (vs still streaming).
    @Volatile private var running = false
    @Volatile private var cancelling = false
    // Set once the run completes and the artifact is read; gates the result card.
    private var foundSwitch: String? = null
    // True iff the artifact's conf was exactly "verified" (long-tested on THIS device): the tester
    // already proved it, so Apply locks it directly -- no "connect charger" gate, no second retest (A1).
    private var foundVerified: Boolean = false
    private var foundKlass: String = ""
    private var foundConf: String = ""
    // v6.4.3: every working switch the tester ranked (recommended + alts incl risky), so the finder's
    // result card can offer the FULL tagged list -- the user picks ANY of them, no exception.
    private var foundAlts: List<VerifiedSwitch.Alt> = emptyList()
    private var foundRecommended: VerifiedSwitch.Alt? = null
    // Posts streamed lines + the completion callback onto the UI thread (the libsu stdout
    // CallbackList delivers them on a worker thread).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Full log text kept in a buffer (not just the TextView), so it survives a config-change or a
    // background-kill recreation via onSaveInstanceState -- the TextView's own text would be lost.
    private val logBuffer = StringBuilder()
    // True once a scan has actually started (the pre-test dialog was dismissed). On a restore this
    // gates whether we re-show the dialog (not started) or restore the log/result (already started).
    private var started = false
    // The mode the current run was started with; saved/restored so a reconnect knows the scan type.
    private var runMode = "--quick"

    private val ASSET = "acc-compat.sh"
    private val TESTER_PATH = "/data/local/tmp/acc-compat.sh"
    private val STOPF = "/data/local/tmp/.acc-compat-stop"   // cooperative cancel flag the tester polls
    private val DATA_DIR = "/data/adb/vr25/acc-data"         // both crash blacklists live here

    // libsu runs EVERY Shell.su() job on ONE shared global root shell. The tester is .submit()'d to that
    // shell and holds it (streaming) for the whole run, so a Shell.su(...) cancel/kill command QUEUES behind
    // it and never executes until the run ends -- THE reason Cancel did nothing. Control commands therefore
    // run on a SEPARATE root shell, so they execute immediately while the tester holds the main one.
    private val controlShell: Shell by lazy { Shell.newInstance() }
    private fun controlExec(cmd: String): Boolean =
        try { controlShell.newJob().add(cmd).exec().isSuccess }
        catch (e: Exception) { LogExt().e(javaClass.simpleName, "controlExec failed: $e"); false }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivitySwitchFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The tester run takes minutes; keep the screen on so the device doesn't sleep mid-run. If an
        // aggressive OEM still destroys this activity, the scan is no longer aborted (onDestroy only kills
        // on a real finish) and restoreFrom()/reconnectToRun() pick it back up. Flag clears on finish.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setSupportActionBar(binding.switchFinderToolbar)
        supportActionBar?.title = getString(R.string.find_switch_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.switchFinderCancelButton.setOnClickListener { onCancelClick() }
        binding.switchFinderApplyButton.setOnClickListener { onApplyFoundClick() }

        // Rotation no longer reaches here (configChanges keeps this activity alive). This path is only
        // hit on a genuine recreation -- a background kill / "Don't keep activities" -- so restore the
        // run instead of wiping the log and re-prompting (the old "blank page" + lost-scan bug).
        if (savedInstanceState != null && savedInstanceState.getBoolean(KEY_STARTED, false))
            restoreFrom(savedInstanceState)
        else
            showPreTestDialog()
    }

    /**
     * Re-render the screen after a background-kill recreation: replay the saved log, header and result
     * card, and -- if the scan was still in flight -- reconnect to the (still-running, NOT killed) tester
     * and wait for it to finish. Never re-shows the pre-test dialog; the user already started a scan.
     */
    private fun restoreFrom(state: Bundle)
    {
        started = true
        runMode = state.getString(KEY_MODE, "--quick")
        foundSwitch = state.getString(KEY_FOUND)
        foundVerified = state.getBoolean(KEY_VERIFIED, false)
        logBuffer.append(state.getString(KEY_LOG, ""))
        binding.switchFinderLog.text = logBuffer.toString()
        binding.switchFinderLogScroll.post { binding.switchFinderLogScroll.fullScroll(View.FOCUS_DOWN) }
        setHeader(state.getString(KEY_HEADER, getString(R.string.find_switch_status_starting)))

        when
        {
            state.getBoolean(KEY_RUNNING, false) -> reconnectToRun()   // scan still going in the background
            state.getBoolean(KEY_RESULT_SHOWN, false) -> onRunFinished()  // finished: re-read artifact + show card
            else -> { binding.switchFinderProgress.visibility = View.GONE }
        }
    }

    /**
     * Hermetic-test contract: explain what the run does (pauses ACC, tests the charging controls one by one,
     * restores everything, only SUGGESTS a switch) and the preconditions for a clean result (40-80%, cool,
     * plugged, screen on) BEFORE we touch anything. Only on OK do we start. The tester also smart-gates the
     * unsafe/useless extremes (>=85%, <15%, hot) and writes result=precondition if the user proceeds anyway.
     */
    private fun showPreTestDialog()
    {
        val sticky = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val lvl = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scl = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (lvl >= 0 && scl > 0) lvl * 100 / scl else -1
        val tRaw = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tC = if (tRaw > 0) tRaw / 10 else -1
        val body = StringBuilder(getString(R.string.find_switch_precheck_body))
        if (pct in 0..100)
        {
            body.append("\n\n").append(getString(R.string.find_switch_precheck_now, pct))
            // explicit space: AAPT strips the temp string's leading space, so "84%.Temperature" needs it here
            if (tC > 0) body.append(' ').append(getString(R.string.find_switch_precheck_temp, tC))
        }
        // Mode is a CHOICE shown as two clear full-width buttons in a custom view, not three cramped
        // footer buttons (which overflowed/stacked badly) nor message()+listItems() (which this library
        // renders as either/or -- the long explainer suppressed the list). Explainer + both choices + a
        // Cancel footer button always show together, on any width.
        val content = layoutInflater.inflate(R.layout.dialog_switch_finder_modes, null)
        content.findViewById<android.widget.TextView>(R.id.dlg_sf_explainer).text = body
        val dialog = MaterialDialog(this).show {
            cancelable(false)
            cancelOnTouchOutside(false)
            title(R.string.find_switch_precheck_title)
            customView(view = content, scrollable = true)
            negativeButton(android.R.string.cancel) { finish() }
        }
        content.findViewById<android.widget.Button>(R.id.dlg_sf_quick).setOnClickListener {
            dialog.dismiss(); startRun("--quick")
        }
        content.findViewById<android.widget.Button>(R.id.dlg_sf_deep).setOnClickListener {
            dialog.dismiss()
            MaterialDialog(this).show {
                title(text = "Deep scan")
                message(text = "Standard finds your switches without touching the charger.\n\nHighest accuracy ALSO asks you to UNPLUG the charger briefly -- this reveals hidden vendor switches the firmware only writes when power leaves, and works even on phones with no current sensor. Your charger may need a re-plug afterwards.")
                positiveButton(text = "Standard") { startRun("--complete") }
                negativeButton(text = "Highest accuracy") { startRun("--complete --unplug") }
            }
        }
    }

    /** Persist enough to rebuild the screen if the OS kills + recreates us while backgrounded. */
    override fun onSaveInstanceState(outState: Bundle)
    {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTED, started)
        outState.putBoolean(KEY_RUNNING, running)
        outState.putString(KEY_MODE, runMode)
        // Tail only: a deep scan's full log can approach the ~1MB binder transaction ceiling and a
        // TransactionTooLargeException here would lose ALL saved state (incl. the started flag),
        // stranding a still-running root scan. The live TextView keeps the full scrollback.
        outState.putString(KEY_LOG, logBuffer.toString().takeLast(50_000))
        outState.putString(KEY_HEADER, binding.switchFinderStatus.text?.toString())
        outState.putString(KEY_FOUND, foundSwitch)
        outState.putBoolean(KEY_VERIFIED, foundVerified)
        outState.putBoolean(KEY_RESULT_SHOWN, binding.switchFinderResultCard.visibility == View.VISIBLE)
    }

    private companion object
    {
        const val KEY_STARTED = "sf_started"
        const val KEY_RUNNING = "sf_running"
        const val KEY_MODE = "sf_mode"
        const val KEY_LOG = "sf_log"
        const val KEY_HEADER = "sf_header"
        const val KEY_FOUND = "sf_found"
        const val KEY_VERIFIED = "sf_verified"
        const val KEY_RESULT_SHOWN = "sf_result_shown"
    }

    /** Ticks the "Ns elapsed" line once a second while the run is in progress. */
    private fun startElapsedCounter()
    {
        val startedAt = SystemClock.elapsedRealtime()
        launch {
            while (isActive && running)
            {
                val secs = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
                binding.switchFinderElapsed.text = getString(R.string.find_switch_elapsed, secs)
                delay(1000L)
            }
        }
    }

    /**
     * Extracts the bundled tester (off the main thread), then streams its stdout line-by-line
     * into the log via a libsu [CallbackList], submitting the run asynchronously so the UI stays
     * responsive.
     */
    private fun startRun(mode: String)
    {
        running = true
        startElapsedCounter()
        started = true
        runMode = mode
        launch {
            val extracted = withContext(Dispatchers.IO) { extractTester() }
            if (isFinishing || isActivityDestroyed) return@launch

            if (!extracted)
            {
                running = false
                setHeader(getString(R.string.find_switch_status_error))
                binding.switchFinderProgress.visibility = View.GONE
                appendLine(getString(R.string.find_switch_extract_failed))
                return@launch
            }

            // CallbackList delivers each stdout line on the executor we pass; route that executor
            // through the main-thread Handler so onAddElement (and our UI mutations) run on Main.
            // The shell itself still streams off the main thread, so the UI stays responsive.
            val live = object : CallbackList<String>(Executor { mainHandler.post(it) })
            {
                override fun onAddElement(line: String?)
                {
                    val text = line ?: return
                    if (isFinishing || isActivityDestroyed) return
                    appendLine(text)
                    updateHeaderFor(text)
                }
            }

            appendLine(if (mode.contains("--complete")) (if (mode.contains("--unplug")) "▶ Deep scan + highest accuracy (you'll be asked to unplug)" else "▶ Deep scan (all switches)") else "▶ Quick scan")
            shellJob = Shell.su("sh $TESTER_PATH $mode").to(live)
            shellJob!!.submit { _ ->
                // Completion callback. Bounce onto Main, read the artifact and show the result
                // card. Guard against a late callback after the activity has already closed.
                mainHandler.post {
                    if (isFinishing || isActivityDestroyed || cancelling) return@post
                    running = false
                    onRunFinished()
                }
            }
        }
    }

    /**
     * Copies the bundled asset to filesDir, then `cp`s it into /data/local/tmp as root and marks
     * it executable. Mirrors the path the activity later runs (`sh <tester>`). Returns true on
     * success.
     */
    @androidx.annotation.WorkerThread
    private fun extractTester(): Boolean
    {
        return try
        {
            val appFile = java.io.File(filesDir, ASSET)
            assets.open(ASSET).use { input ->
                appFile.outputStream().use { output -> input.copyTo(output) }
            }
            Shell.su("rm -f $STOPF /data/local/tmp/acc-compat-verified; cp ${appFile.absolutePath} $TESTER_PATH; chmod 0755 $TESTER_PATH").exec().isSuccess
        }
        catch (ex: Exception)
        {
            LogExt().e(javaClass.simpleName, "extractTester() failed: $ex")
            false
        }
    }

    /** Appends one line to the log and scrolls to the bottom so the newest output stays visible. */
    private fun appendLine(line: String)
    {
        logBuffer.append(line).append('\n')
        binding.switchFinderLog.append(line + "\n")
        binding.switchFinderLogScroll.post {
            binding.switchFinderLogScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setHeader(text: String)
    {
        binding.switchFinderStatus.text = text
    }

    /**
     * Maps the tester's own section markers to a friendly, human status header. The tester prints
     * "==== LAYER N ...", a "VERDICT" banner, a "RECOMMENDED=" summary line and "weak_charger"
     * warnings; these tell the user what phase the run is in.
     */
    private fun updateHeaderFor(line: String)
    {
        when
        {
            line.contains("reset to NATIVE", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_resetting))
            line.contains("weak_charger=1") || line.contains("WEAK CHARGER", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_weak_charger))
            // contains, not startsWith: since 7.1.9 the engine stamps "[t+Ns d+Ns] " in front of
            // every "==== LAYER" header for per-step timing, so a prefix match never fires.
            line.contains("==== LAYER 4") || line.contains("==== LAYER 6") ->
                setHeader(getString(R.string.find_switch_status_testing))
            line.contains("==== LAYER") ->
                setHeader(getString(R.string.find_switch_status_probing))
            line.contains("RESTORING", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_verifying))
            line.startsWith("RECOMMENDED=") || line.contains("VERDICT", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_done))
        }
    }

    /**
     * After a background-kill recreation we did NOT kill the tester (onDestroy only kills on a real
     * finish), so the scan is still running in its own root process. We can't re-attach the live
     * stdout stream, so show the restored (frozen) log, spin, and POLL on a SEPARATE root shell (so we
     * don't queue behind the tester) until it exits -- then read the artifact via onRunFinished().
     */
    private fun reconnectToRun()
    {
        running = true
        binding.switchFinderProgress.visibility = View.VISIBLE
        setHeader(getString(R.string.find_switch_status_reconnected))
        startElapsedCounter()
        launch {
            while (isActive)
            {
                val alive = withContext(Dispatchers.IO)
                {
                    try { controlShell.newJob().add("pgrep -f '[a]cc-compat[.]sh' >/dev/null").exec().isSuccess }
                    catch (e: Exception) { false }
                }
                if (!alive) break
                delay(2000)
            }
            if (isFinishing || isActivityDestroyed) return@launch
            running = false
            onRunFinished()
        }
    }

    /**
     * Called once the tester process exits. Stops the spinner, then reads the verified-switch
     * artifact and shows either the "Found:" result card (with Apply) or a friendly no-switch
     * message.
     */
    private fun onRunFinished()
    {
        if (cancelling) return
        binding.switchFinderProgress.visibility = View.GONE
        binding.switchFinderCancelButton.setText(R.string.close)

        launch {
            val result = withContext(Dispatchers.IO)
            {
                try { VerifiedSwitch.detect() }
                catch (ex: Exception)
                {
                    LogExt().e(javaClass.simpleName, "VerifiedSwitch.detect() failed: $ex")
                    VerifiedSwitch.None
                }
            }

            if (isFinishing || isActivityDestroyed) return@launch

            when (result)
            {
                is VerifiedSwitch.Verified ->
                {
                    foundAlts = result.alts
                    foundRecommended = VerifiedSwitch.Alt(result.switch, result.klass, result.conf, "", result.recLatch, "", result.recStability)
                    setHeader(getString(R.string.find_switch_status_done))
                    renderFound(result.switch, result.klass, result.conf, caveat = false)
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
                is VerifiedSwitch.NeedsTest ->
                {
                    foundAlts = result.alts
                    foundRecommended = VerifiedSwitch.Alt(result.switch, result.klass, result.conf, "", result.recLatch, "", result.recStability)
                    setHeader(getString(R.string.find_switch_status_done))
                    renderFound(result.switch, result.klass, result.conf, caveat = true)
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
                is VerifiedSwitch.Precondition ->
                {
                    setHeader(getString(R.string.find_switch_status_precondition))
                    binding.switchFinderResultText.text =
                        result.reason.ifBlank { getString(R.string.find_switch_none) }
                    binding.switchFinderResultCaveat.visibility = View.GONE
                    binding.switchFinderApplyButton.visibility = View.GONE
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
                is VerifiedSwitch.DeviceMismatch ->
                { // a real artifact exists but belongs to ANOTHER device -- "try a stronger charger"
                  // advice would be wrong here; say what actually happened.
                    setHeader(getString(R.string.find_switch_status_no_switch))
                    binding.switchFinderResultText.text = getString(R.string.find_switch_device_mismatch)
                    binding.switchFinderResultCaveat.visibility = View.GONE
                    binding.switchFinderApplyButton.visibility = View.GONE
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
                else ->
                {
                    setHeader(getString(R.string.find_switch_status_no_switch))
                    binding.switchFinderResultText.text = getString(R.string.find_switch_none)
                    binding.switchFinderResultCaveat.visibility = View.GONE
                    binding.switchFinderApplyButton.visibility = View.GONE
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * v6.4.3: render the chosen switch in the result card and, when the tester ranked MORE than one
     * working switch, make the card tappable to open the FULL picker (every switch, tagged + selectable).
     */
    private fun renderFound(switch: String, klass: String, conf: String, caveat: Boolean)
    {
        foundSwitch = switch
        foundVerified = (conf == "verified")
        foundKlass = klass
        foundConf = conf
        binding.switchFinderResultText.text = getString(R.string.find_switch_found, switch, klass, conf)
        binding.switchFinderResultCaveat.visibility = if (caveat) View.VISIBLE else View.GONE
        binding.switchFinderApplyButton.visibility = View.VISIBLE
        binding.switchFinderApplyButton.isEnabled = true
        val total = (listOfNotNull(foundRecommended) + foundAlts)
            .distinctBy { it.switch.trim() }.size
        if (total > 1)
        {
            // A real, prominent button so the user can open the full list and pick the best of ALL
            // switches AMPS found (the old tap-the-text affordance was easy to miss).
            binding.switchFinderAllButton.text = getString(R.string.find_switch_see_all, total)
            binding.switchFinderAllButton.visibility = View.VISIBLE
            binding.switchFinderAllButton.setOnClickListener { showAllSwitchesDialog() }
            binding.switchFinderResultText.setOnClickListener { showAllSwitchesDialog() }
        }
        else
        {
            binding.switchFinderAllButton.visibility = View.GONE
            binding.switchFinderResultText.setOnClickListener(null)
        }
    }

    /**
     * v6.4.3: the FULL switch picker (was only reachable from the config editor). Lists the recommended
     * switch AND every alternative the tester ranked -- each tagged with class + confidence + daemon
     * stability (incl. risky/leaky, clearly marked) -- so the user can navigate and select ANY of them,
     * no exception. Picking one re-targets the Apply button down the same verified-direct / live-test
     * path, so a risky pick is still gated by the live test before any pin.
     */
    private fun showAllSwitchesDialog()
    {
        val rec = foundRecommended ?: return
        val all = (listOf(rec) + foundAlts).distinctBy { it.switch.trim() }
        val labelMap = uniqueSwitchLabels(all.map { it.switch })
        val labels = all.mapIndexed { i, a ->
            val node = labelMap[a.switch] ?: switchLabel(a.switch)
            val badge = a.klass.ifBlank { "switch" }.uppercase()
            val cf = if (a.conf == "verified") "verified" else a.conf.ifBlank { "needs test" }
            val star = if (i == 0) "  ★ recommended" else ""
            val latch = if (a.latch) " · latches" else ""
            val stab = when (a.stability)
            {
                "daemon-held" -> " · daemon-held (re-arms, the daemon holds it)"
                "leaky"       -> " · ⚠ re-arms even when re-applied (risky)"
                "reassert"    -> " · ⚠ unstable (daemon must re-apply)"
                else          -> ""
            }
            "$badge — $node\n$cf$latch$stab$star"
        }
        MaterialDialog(this@SwitchFinderActivity).show {
            title(text = getString(R.string.verified_switch_all_title))
            listItems(items = labels) { _, index, _ ->
                val pick = all[index]
                renderFound(pick.switch, pick.klass, pick.conf, caveat = pick.conf != "verified")
                binding.switchFinderApplyStatus.text = ""
            }
        }
    }

    /** Human label for a switch spec: "parent/node" for a single node (so the three current_max
     *  paths read gccd/current_max, main-charger/current_max, usb/current_max -- not three identical
     *  "current_max"), or "grouped (N paths)" for a multi-node grouped ctrl spec. */
    private fun switchLabel(spec: String): String
    {
        val toks = spec.trim().split(' ')
        val paths = toks.filter { it.startsWith("/") }
        if (paths.size > 1) return "grouped (${paths.size} paths)"
        val p = paths.firstOrNull() ?: toks.firstOrNull().orEmpty()
        val segs = p.trim('/').split('/').filter { it.isNotEmpty() }
        return when { segs.size >= 2 -> segs.takeLast(2).joinToString("/"); segs.size == 1 -> segs[0]; else -> p }
    }

    /** Labels that are UNIQUE across the given specs. The short "parent/node" from switchLabel()
     *  collides when the SAME kernel node is exposed at two different paths -- e.g. OnePlus/Oplus
     *  publish it at BOTH /sys/class/oplus_chg/battery/mmi_charging_enable and
     *  /sys/devices/virtual/oplus_chg/battery/mmi_charging_enable, and last-two-segments makes both
     *  read "battery/mmi_charging_enable" so the user can't tell the two rows apart (field report:
     *  "I told him to pick /sys/devices/virtual/...mmi_charging_enable but he couldn't find it").
     *  On a collision, fall back to the full path (minus /sys/) so each row is distinguishable. */
    private fun uniqueSwitchLabels(specs: List<String>): Map<String, String> {
        val base = specs.associateWith { switchLabel(it) }
        val counts = base.values.groupingBy { it }.eachCount()
        return specs.associateWith { spec ->
            val short = base.getValue(spec)
            if ((counts[short] ?: 0) > 1) {
                val p = spec.trim().split(' ').firstOrNull { it.startsWith("/") } ?: spec.trim()
                p.removePrefix("/sys/").removePrefix("/").ifBlank { short }
            } else short
        }
    }

    /**
     * Apply the found switch using the SAME path the editor's Apply & Lock card uses: require the
     * phone to be charging, run the live `acca -t` retest (with the exit 0/15 "works" fix), and
     * only on a pass write the manual pin (charging_switch = "<sw> --", automatic OFF). Shows the
     * inline spinner + status instead of a silent wait (TASK 4).
     */
    private fun onApplyFoundClick()
    {
        val switch = foundSwitch ?: return

        launch {
            // Decide from class+conf (single source of truth in VerifiedSwitch.applyMode):
            // PIN_DIRECT (verified) locks
            // with no acc -t; LEVEL_RERUN (an unproven %-cap acc -t can only FALSE-fail) asks for a
            // lower-% re-run; LIVE_TEST (cut / bypass / drain) runs acc -t first.
            val mode = VerifiedSwitch.applyMode(foundKlass, foundConf)
            if (mode == VerifiedSwitch.ApplyMode.PIN_DIRECT)
            {
                // Check EVERY node of the spec, not just the first: a grouped multi-path switch with
                // vanished paths invalidate the saved result.
                val present = withContext(Dispatchers.IO) { VerifiedSwitch.pathsExist(switch) }
                if (present)
                {
                    binding.switchFinderApplyButton.isEnabled = false
                    binding.switchFinderApplyProgress.visibility = View.VISIBLE
                    binding.switchFinderApplyStatus.text = ""
                    val ok = try { withContext(Dispatchers.IO) { pinAndKick(switch) } }
                    catch (ex: Exception) { LogExt().e(javaClass.simpleName, "verified pin failed: $ex"); false }
                    if (isFinishing || isActivityDestroyed) return@launch
                    binding.switchFinderApplyProgress.visibility = View.GONE
                    if (ok)
                    {
                        binding.switchFinderApplyStatus.setText(R.string.find_switch_apply_locked)
                        Toast.makeText(this@SwitchFinderActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
                    }
                    else
                    {
                        binding.switchFinderApplyStatus.setText(R.string.error_occurred)
                        binding.switchFinderApplyButton.isEnabled = true
                    }
                    return@launch
                }
                Toast.makeText(this@SwitchFinderActivity, R.string.error_occurred, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (mode == VerifiedSwitch.ApplyMode.LEVEL_RERUN)
            { // an unproven %-cap: acc -t can only FALSE-fail it from below the cap. Ask for a re-run.
                MaterialDialog(this@SwitchFinderActivity).show {
                    title(R.string.find_switch_title)
                    message(R.string.verified_switch_level_rerun)
                    positiveButton(android.R.string.ok)
                }
                return@launch
            }

            val charging = try { Acc.instance.isChargerPlugged() }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "isChargerPlugged() failed: $ex")
                false
            }

            if (isFinishing || isActivityDestroyed) return@launch

            if (!charging)
            { // Cannot live-test unplugged: prompt to plug in instead of testing.
                MaterialDialog(this@SwitchFinderActivity).show {
                    title(R.string.find_switch_title)
                    message(R.string.verified_switch_plug_to_apply)
                    positiveButton(android.R.string.ok)
                }
                return@launch
            }

            // Inline progress: spinner + "Testing the switch live…", Apply disabled meanwhile.
            binding.switchFinderApplyButton.isEnabled = false
            binding.switchFinderApplyProgress.visibility = View.VISIBLE
            binding.switchFinderApplyStatus.setText(R.string.find_switch_apply_testing)

            val passed = try { Acc.instance.testChargingSwitch(switch) == 0 }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "testChargingSwitch() failed: $ex")
                false
            }

            if (isFinishing || isActivityDestroyed) return@launch

            if (!passed)
            {
                binding.switchFinderApplyProgress.visibility = View.GONE
                binding.switchFinderApplyStatus.setText(R.string.verified_switch_failed_live_test)
                binding.switchFinderApplyButton.isEnabled = true
                return@launch
            }

            // Passed: write the manual pin off the main thread (automatic OFF appends " --").
            val written = try { withContext(Dispatchers.IO) { pinAndKick(switch) } }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "updateAccChargingSwitch() failed: $ex")
                false
            }

            if (isFinishing || isActivityDestroyed) return@launch

            binding.switchFinderApplyProgress.visibility = View.GONE
            if (written)
            {
                binding.switchFinderApplyStatus.setText(R.string.find_switch_apply_locked)
                Toast.makeText(this@SwitchFinderActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
                if (foundConf == "pump-needs-long-test")
                    Toast.makeText(this@SwitchFinderActivity, R.string.verified_switch_pump_note, Toast.LENGTH_LONG).show()
            }
            else
            {
                binding.switchFinderApplyStatus.setText(R.string.error_occurred)
                binding.switchFinderApplyButton.isEnabled = true
            }
        }
    }

    /**
     * Pin the switch then kick the daemon so the new switch takes effect IMMEDIATELY (A2: "charging
     * doesn't stop after selecting a working switch"). Writing `charging_switch=… --` from a non-daemon
     * caller makes ACC's write-config set `.user-locked` (survives reboot, never auto-replaced); the
     * restart re-inits the daemon onto it. Safe post-D2 (exxit no longer un-caps on restart at the limit).
     * Runs on a worker thread (callers wrap it in Dispatchers.IO).
     */
    private suspend fun pinAndKick(switch: String): Boolean =
        VerifiedSwitch.pinAndRestart(Acc.instance, switch)

    /** Cancel: stop the tester, restore charging, and only THEN close -- with on-screen progress so the
     *  page never just blanks while the phone is left mid-test. */
    private fun onCancelClick()
    {
        if (!running) { finish(); return }
        cancelling = true
        running = false
        setHeader(getString(R.string.find_switch_status_cancelling))
        binding.switchFinderProgress.visibility = View.VISIBLE
        binding.switchFinderCancelButton.isEnabled = false
        binding.switchFinderApplyButton.visibility = View.GONE
        launch {
            val recovered = killTesterAndRecover()
            if (isFinishing || isActivityDestroyed) return@launch
            Toast.makeText(this@SwitchFinderActivity, if (recovered) R.string.find_switch_cancelled_restored else R.string.error_occurred, Toast.LENGTH_LONG).show()
            binding.switchFinderProgress.visibility = View.GONE
            binding.switchFinderCancelButton.isEnabled = true
            binding.switchFinderCancelButton.setText(R.string.close)
            if (recovered) finish() else setHeader(getString(R.string.error_occurred))
        }
    }

    /**
     * Robust stop + recover. `pkill -f acc-compat` alone only SIGTERMs the top `sh`; its children (the
     * layer sub-tests + their `sleep`s) survive and the run keeps going. So: request cooperative exit, then SIGTERM the tester and its
     * scan processes if needed, wait for exit, THEN restart ACC so charging
     * can resume without interrupting the restore trap (the user-reported "phone doesn't recover after cancel").
     * submit() runs on libsu's own worker so it completes even after this activity closes.
     */
    private fun killTester() {
        cancelling = true
        Thread { runBlocking { killTesterAndRecover() } }.start()
    }

    /** Blocking variant (for the Cancel button): kill + recover synchronously so we can show progress and
     *  only close once charging is being restored. */
    private suspend fun killTesterAndRecover(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!controlExec("echo 1 > $STOPF")) return@withContext false
            repeat(2) { attempt ->
                repeat(30) {
                    // Exit 1 means no matching process; a shell/search error is not proof of exit.
                    if (controlExec("pgrep -f '[a]cc-compat[.]sh' >/dev/null 2>&1; [ \$? = 1 ]")) {
                        controlExec(Acc.instance.getAccRestartDaemon())
                        delay(2500)
                        val status = Acc.instance.getAccRestartDaemon().removeSuffix(" restart")
                        return@withContext controlExec("$status >/dev/null 2>&1; rc=\$?; [ \"\$rc\" = 0 ] || [ \"\$rc\" = 8 ]")
                    }
                    delay(2000)
                }
                if (attempt == 0) controlExec("pkill -TERM -f '[a]cc-compat[.]sh' 2>/dev/null")
            }
            // Do not SIGKILL an EXIT trap that may still be restoring charging controls.
            false
        } catch (ex: Exception) {
            LogExt().e(javaClass.simpleName, "killTesterAndRecover() failed: $ex")
            false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean
    {
        menuInflater.inflate(R.menu.switch_finder_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        when (item.itemId)
        {
            android.R.id.home   -> { onBackPressed(); return true }
            R.id.menu_copy_log  -> { copyLog();  return true }
            R.id.menu_share_log -> { shareLog(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Copy the full run log to the clipboard so the user can paste it back to us. */
    private fun copyLog()
    {
        val text = binding.switchFinderLog.text?.toString().orEmpty()
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("acc-compat", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /**
     * Write the run log into filesDir/logs (the only path the app's FileProvider exposes) and fire a
     * share chooser. Sharing a file (not EXTRA_TEXT) so the full multi-KB tester log is never truncated.
     */
    private fun shareLog()
    {
        val text = binding.switchFinderLog.text?.toString().orEmpty()
        if (text.isBlank()) { Toast.makeText(this, R.string.logs_not_found, Toast.LENGTH_SHORT).show(); return }
        try
        {
            val dir = java.io.File(filesDir, "logs").apply { mkdirs() }
            val f = java.io.File(dir, "acc-compat-switch-log.txt")
            f.writeText(text)
            val uri = androidx.core.content.FileProvider.getUriForFile(applicationContext, "mattecarra.accapp.fileprovider", f)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share_log)))
        }
        catch (ex: Exception)
        {
            LogExt().e(javaClass.simpleName, "shareLog() failed: $ex")
            Toast.makeText(this, R.string.error_occurred, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy()
    {
        // Only kill the tester when the user is genuinely LEAVING (Back / Cancel / Close -> isFinishing).
        // On a transient teardown -- a config change, or a background kill where the task lives on --
        // leave the scan running: it has its own EXIT-trap restore + deadline guard, and we reconnect to
        // it on recreation (restoreFrom -> reconnectToRun). Killing it here was what aborted the scan on
        // rotation / "Don't keep activities", producing the blank, re-prompted screen.
        if (running && isFinishing)
        {
            running = false
            killTester()
        }
        super.onDestroy()
    }
}
