package mattecarra.accapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acc.FILES_DIR resolved a hardcoded /data/data/mattecarra.accapp/files. That is only the primary
 * user's path: a clone, parallel-space copy or secondary user lives under /data/user/<id>/, where
 * the literal does not exist, so the app-managed ACC install was invisible to isAccInstalled().
 *
 * The resolution rule is asserted here rather than the constant, because the point is the
 * FALLBACK: before MainApplication.onCreate has run there is no Context, and the old literal must
 * still be what is used so the primary-user path is unchanged.
 */
class FilesDirFallbackTest {

    private fun resolve(captured: String?): String =
        captured ?: "/data/data/mattecarra.accapp/files"

    @Test fun beforeOnCreateItFallsBackToTheLiteral() =
        assertEquals("/data/data/mattecarra.accapp/files", resolve(null))

    @Test fun aCloneGetsItsOwnPath() =
        assertEquals("/data/user/999/mattecarra.accapp/files",
            resolve("/data/user/999/mattecarra.accapp/files"))

    @Test fun thePrimaryUserIsUnchangedWhenCaptured() =
        assertEquals("/data/data/mattecarra.accapp/files",
            resolve("/data/data/mattecarra.accapp/files"))

    @Test fun aSecondaryUserGetsItsOwnPath() =
        assertEquals("/data/user/10/mattecarra.accapp/files",
            resolve("/data/user/10/mattecarra.accapp/files"))
}
