package mattecarra.accapp.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.afollestad.materialdialogs.MaterialDialog
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // Set once the run completes and the artifact is read; gates the result card.
    private var foundSwitch: String? = null
    // Posts streamed lines + the completion callback onto the UI thread (the libsu stdout
    // CallbackList delivers them on a worker thread).
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ASSET = "acc-compat-v5.5.sh"
    private val TESTER_PATH = "/data/local/tmp/acc-compat-v5.5.sh"

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivitySwitchFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.switchFinderToolbar)
        supportActionBar?.title = getString(R.string.find_switch_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.switchFinderCancelButton.setOnClickListener { onCancelClick() }
        binding.switchFinderApplyButton.setOnClickListener { onApplyFoundClick() }

        startElapsedCounter()
        startRun()
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
    private fun startRun()
    {
        running = true
        launch {
            val extracted = withContext(Dispatchers.IO) { extractTester() }
            if (isFinishing || isDestroyed) return@launch

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
                    if (isFinishing || isDestroyed) return
                    appendLine(text)
                    updateHeaderFor(text)
                }
            }

            shellJob = Shell.su("sh $TESTER_PATH").to(live)
            shellJob!!.submit { _ ->
                // Completion callback. Bounce onto Main, read the artifact and show the result
                // card. Guard against a late callback after the activity has already closed.
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
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
            Shell.su("cp ${appFile.absolutePath} $TESTER_PATH; chmod 0755 $TESTER_PATH").exec().isSuccess
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
            line.startsWith("==== LAYER 4") || line.startsWith("==== LAYER 6") ->
                setHeader(getString(R.string.find_switch_status_testing))
            line.startsWith("==== LAYER") ->
                setHeader(getString(R.string.find_switch_status_probing))
            line.contains("RESTORING", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_verifying))
            line.startsWith("RECOMMENDED=") || line.contains("VERDICT", ignoreCase = true) ->
                setHeader(getString(R.string.find_switch_status_done))
        }
    }

    /**
     * Called once the tester process exits. Stops the spinner, then reads the verified-switch
     * artifact and shows either the "Found:" result card (with Apply) or a friendly no-switch
     * message.
     */
    private fun onRunFinished()
    {
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

            if (isFinishing || isDestroyed) return@launch

            when (result)
            {
                is VerifiedSwitch.Verified ->
                {
                    foundSwitch = result.switch
                    setHeader(getString(R.string.find_switch_status_done))
                    binding.switchFinderResultText.text =
                        getString(R.string.find_switch_found, result.switch, result.klass, result.conf)
                    binding.switchFinderResultCaveat.visibility = View.GONE
                    binding.switchFinderResultCard.visibility = View.VISIBLE
                }
                is VerifiedSwitch.NeedsTest ->
                {
                    foundSwitch = result.switch
                    setHeader(getString(R.string.find_switch_status_done))
                    binding.switchFinderResultText.text =
                        getString(R.string.find_switch_found, result.switch, result.klass, result.conf)
                    binding.switchFinderResultCaveat.visibility = View.VISIBLE
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
     * Apply the found switch using the SAME path the editor's Apply & Lock card uses: require the
     * phone to be charging, run the live `acca -t` retest (with the exit 0/15 "works" fix), and
     * only on a pass write the manual pin (charging_switch = "<sw> --", automatic OFF). Shows the
     * inline spinner + status instead of a silent wait (TASK 4).
     */
    private fun onApplyFoundClick()
    {
        val switch = foundSwitch ?: return

        launch {
            val charging = try { Acc.instance.isBatteryCharging() }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "isBatteryCharging() failed: $ex")
                false
            }

            if (isFinishing || isDestroyed) return@launch

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

            if (isFinishing || isDestroyed) return@launch

            if (!passed)
            {
                binding.switchFinderApplySpinner.visibility = View.GONE
                binding.switchFinderApplyStatus.setText(R.string.verified_switch_failed_live_test)
                binding.switchFinderApplyButton.isEnabled = true
                return@launch
            }

            // Passed: write the manual pin off the main thread (automatic OFF appends " --").
            val written = try
            {
                withContext(Dispatchers.IO) { Acc.instance.updateAccChargingSwitch(switch, false) }
            }
            catch (ex: Exception)
            {
                LogExt().e(javaClass.simpleName, "updateAccChargingSwitch() failed: $ex")
                false
            }

            if (isFinishing || isDestroyed) return@launch

            binding.switchFinderApplySpinner.visibility = View.GONE
            if (written)
            {
                binding.switchFinderApplyStatus.setText(R.string.find_switch_apply_locked)
                Toast.makeText(this@SwitchFinderActivity, R.string.verified_switch_applied, Toast.LENGTH_LONG).show()
            }
            else
            {
                binding.switchFinderApplyStatus.setText(R.string.error_occurred)
                binding.switchFinderApplyButton.isEnabled = true
            }
        }
    }

    /** Cancel: kill the running tester (best-effort, off the main thread) and close. */
    private fun onCancelClick()
    {
        if (running)
        {
            running = false
            killTester()
        }
        finish()
    }

    /**
     * Best-effort kill of the tester process tree. The tester traps EXIT and restores native
     * charging itself, so pkill triggers its own safe-restore path.
     */
    private fun killTester()
    {
        // libsu's Shell.Job has no cancel handle, so kill the process tree by name. The tester's
        // own EXIT trap then runs its safe-restore (re-enables native charging, restarts ACC).
        // submit() runs it asynchronously on libsu's own worker, so it survives our scope being
        // cancelled in onDestroy (a coroutine launch here would be cancelled before it ran).
        Shell.su("pkill -f acc-compat-v5.5").submit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy()
    {
        // Kill the tester if the activity is torn down mid-run so it never keeps the daemon
        // stopped / charging uncontrolled after the screen is gone (the tester's own EXIT trap
        // restores native charging when it dies).
        if (running)
        {
            running = false
            killTester()
        }
        super.onDestroy()
    }
}
