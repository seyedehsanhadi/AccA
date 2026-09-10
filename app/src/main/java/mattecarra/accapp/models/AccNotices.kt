package mattecarra.accapp.models

/**
 * What ACC SAID while a setting was applied.
 *
 * `acc -s` does not only succeed or fail: when a temperature is out of order, or a resume level
 * would sit above its pause, it CORRECTS the value and explains what it did on stdout. Every
 * caller here ran the command through `Shell.su(...).exec().isSuccess` and dropped that output on
 * the floor, so the app reported a clean success and then showed a value the user never chose,
 * with nothing anywhere to say why it had changed.
 *
 * This holds the last such explanation so the surface that triggered the apply can show it. It is
 * deliberately a plain singleton: the notice belongs to the apply that just happened, not to any
 * one screen, and the alternative was threading a return type through eleven interface methods.
 */
object AccNotices {
    @Volatile private var last: String? = null

    /** Keep any line that reads as ACC explaining an adjustment rather than echoing success. */
    fun record(lines: List<String>) {
        val text = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "\u2705" }
            .filter { line ->
                val l = line.lowercase()
                l.contains("adjust") || l.contains("corrected") || l.contains("clamp") ||
                l.contains("must be") || l.contains("instead of") || l.contains("raised") ||
                l.contains("lowered") || l.contains("ignored")
            }
            .joinToString("\n")
        if (text.isNotBlank()) last = text
    }

    /** Read and clear: a notice is shown once, for the apply that produced it. */
    fun take(): String? {
        val v = last
        last = null
        return v
    }
}
