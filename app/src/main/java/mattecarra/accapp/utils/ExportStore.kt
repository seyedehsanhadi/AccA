package mattecarra.accapp.utils

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * One folder, one mechanism, both directions.
 *
 * Export used to hand the JSON to the share sheet and Import used to read it back through the
 * system document picker, which is two unrelated mechanisms with a user in the middle guessing
 * where the file went. It also does not work: on a Mi A3 (MIUI, Android 10) the picker opens on
 * the Downloads root and that root answers "Can't load content at the moment", so a file AccA had
 * just written could not be picked. Browsing to internal storage in the same picker lists it fine,
 * which places the fault in the Downloads shortcut provider rather than in AccA -- but the user
 * still ends up at a dead screen, and telling them to go around it is not a fix.
 *
 * So AccA owns a folder. Save writes there, Load lists what is there. Nothing in between can fail,
 * and the file is in Download where a file manager or a chat app can reach it.
 *
 * Root, not scoped storage: AccA targets SDK 31 with no storage permission, so it cannot touch
 * /sdcard directly -- but it already has root, and this is the same route `acca --diag` uses to
 * put its bundle in Download. Writes go to the app's own files dir first and are copied out, which
 * keeps the JSON encoding in Kotlin's hands rather than a shell's.
 */
object ExportStore {

    const val DIR = "/sdcard/Download/AccA"

    /** What to show the user. The full path is noise; the last two segments are the useful part. */
    const val DIR_LABEL = "Download/AccA"

    /**
     * A name safe to interpolate into a shell command and to land on a FAT-formatted card.
     * Single quotes are the wrapper used below, so a name may never contain one.
     */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return if (cleaned.isEmpty()) "acca-export" else cleaned.take(64)
    }

    /**
     * A name that is not already taken: "acca-scripts-12.json", then "acca-scripts-12-2.json".
     *
     * An export never overwrites an earlier one. Exporting the same number of scripts twice
     * produces the same generated name, so a plain write would silently replace yesterday's
     * backup with today's -- and the one case where that matters most is the case where the
     * user is exporting because they are about to change something.
     */
    fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        val stem = base.removeSuffix(".json")
        var n = 2
        while ("$stem-$n.json" in taken && n < 1000) n++
        return "$stem-$n.json"
    }

    /**
     * Write [json] to [name] in the folder. Returns the full path, or null if anything failed.
     * [scratch] is the app's own files dir, used as the staging area.
     */
    fun save(scratch: File, name: String, json: String): String? {
        val safe = uniqueName(sanitize(name), list().map { displayName(it) }.toSet())
        val staged = File(File(scratch, "exports").apply { mkdirs() }, safe)
        return try {
            staged.writeText(json)
            // Verify the staged bytes before copying: a short write here would otherwise be
            // copied out faithfully and hand the user a file that imports as fewer entries
            // than they selected.
            if (!staged.exists() || staged.length() < json.toByteArray().size.toLong()) return null
            val dest = "$DIR/$safe"
            val res = Shell.su(
                "mkdir -p '$DIR'",
                "cp -f '${staged.absolutePath}' '$dest'",
                "chmod 0644 '$dest'"
            ).exec()
            if (!res.isSuccess) return null
            // Confirm from the other side. cp can report success and still leave nothing behind
            // on a full or read-only volume.
            val check = Shell.su("[ -s '$dest' ] && echo ok").exec()
            if (check.out.firstOrNull()?.trim() != "ok") null else dest
        } catch (e: Exception) {
            LogExt().e("ExportStore", "save failed: ${e.message}")
            null
        }
    }

    /** Every .json in the folder, newest first, as full paths. Empty when the folder has none. */
    fun list(): List<String> = try {
        Shell.su("ls -1t '$DIR'/*.json 2>/dev/null").exec().out
            .map { it.trim() }
            .filter { it.endsWith(".json") && !it.contains("'") }
    } catch (e: Exception) {
        LogExt().e("ExportStore", "list failed: ${e.message}")
        emptyList()
    }

    /**
     * The exports that look like [prefix] ("acca-script", "acca-profile"), newest first.
     *
     * Scripts and profiles share the folder, so an unfiltered list offers the profile importer a
     * file full of scripts. Picking it is not dangerous - the parser rejects it and says so - but
     * it is a choice the user should not be given in the first place.
     *
     * Falls back to everything when the filter matches nothing, because a file the user renamed,
     * or one that arrived from someone else, is still worth offering rather than hiding behind an
     * empty list.
     */
    fun listOfKind(prefix: String): List<String> {
        val all = list()
        val matching = all.filter { displayName(it).startsWith(prefix) }
        return if (matching.isNotEmpty()) matching else all
    }

    /** The text of one file, or null if it is unreadable or empty. */
    fun read(path: String): String? = try {
        if (path.contains("'")) null
        else Shell.su("cat '$path'").exec().out.joinToString("\n").ifBlank { null }
    } catch (e: Exception) {
        LogExt().e("ExportStore", "read failed: ${e.message}")
        null
    }

    /** Just the filename, for a list the user reads. */
    fun displayName(path: String): String = path.substringAfterLast('/')
}
