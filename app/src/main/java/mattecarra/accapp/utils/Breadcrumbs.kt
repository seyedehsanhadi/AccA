package mattecarra.accapp.utils

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A bounded in-memory ring of recent events ("breadcrumbs") for the centralized diagnostic bundle.
 *
 * ZERO background by design: it holds at most a few hundred short strings in RAM -- no disk I/O, no
 * wakeups, no threads, no scheduling. It does work only while the app is in the foreground (the user
 * is already using it) and is written to a file ONLY on two events the user causes anyway:
 *   - collecting diagnostics (flush()), so the root collector can read the trail, and
 *   - an app crash (installCrashHandler()), which records the stack + trail, then chains to the OS.
 * See .scratch/centralized-diagnosis/design.md (the "zero-background, opt-in verbose" decision).
 */
object Breadcrumbs {
    private const val MAX = 200
    private val ring = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(event: String) {
        if (ring.size >= MAX) ring.pollFirst()
        ring.addLast("${fmt.format(Date())} $event")
    }

    @Synchronized
    fun dump(): String = if (ring.isEmpty()) "(no breadcrumbs)" else ring.joinToString("\n")

    /** Flush the ring to filesDir/logs/breadcrumbs.txt so the (root) collector script can bundle it. */
    fun flush(filesDir: File) {
        try {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            File(dir, "breadcrumbs.txt").writeText(dump() + "\n")
        } catch (_: Exception) { }
    }

    /**
     * Install a global uncaught-exception handler that records the crash stack + the breadcrumb trail
     * to filesDir/logs/last-crash.txt, then chains to the previous handler so the OS still handles the
     * crash normally. Fires only when the app is already crashing -- no ongoing cost.
     */
    fun installCrashHandler(filesDir: File) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val dir = File(filesDir, "logs").apply { mkdirs() }
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                File(dir, "last-crash.txt").writeText(
                    "crash @ ${Date()}\nthread: ${thread.name}\n\n$sw\n" +
                    "---- breadcrumbs (most recent last) ----\n${dump()}\n"
                )
            } catch (_: Exception) { }
            prev?.uncaughtException(thread, ex)
        }
    }
}
