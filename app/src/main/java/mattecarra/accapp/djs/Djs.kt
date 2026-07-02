package mattecarra.accapp.djs

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder

interface DjsInterface {
    suspend fun list(pattern: String = "."): List<DjsSchedule>

    suspend fun append(line: String): Boolean

    suspend fun append(schedule: DjsSchedule): Boolean {
        val command = StringBuilder()
        if(!schedule.isEnabled)
            command.append("//")

        return append(command.append("${schedule.time} ${schedule.command}").toString())
    }

    suspend fun edit(pattern: String, newLine: String): Boolean

    suspend fun edit(schedule: DjsSchedule): Boolean {
        // delete-then-append instead of an in-place `sed`: the old sed edit broke (and allowed
        // shell/regex injection) whenever the schedule command contained #, &, /, or quotes
        // (e.g. paths or apply_on_* commands). Both ops below go through djsc directly.
        //
        // delete-then-append is NOT atomic: snapshot the current entry first and roll it back if
        // the re-append fails (busybox gone, DJS stopped, quoting break). Without the rollback a
        // failed append after a successful delete would permanently destroy the schedule.
        val backup = try {
            list(": accaScheduleId${schedule.scheduleProfileId};").firstOrNull()
        } catch (e: Exception) {
            null
        }

        deleteById(schedule.scheduleProfileId)

        val ok = append(schedule)
        if (!ok && backup != null) {
            // best-effort restore of the original line so a failed edit is not data loss
            try { append(backup) } catch (e: Exception) { e.printStackTrace() }
        }
        return ok
    }

    suspend fun delete(pattern: String): Boolean

    suspend fun deleteById(id: Int): Boolean {
        // Anchor on the trailing ';' that getCommand() writes right after the id
        // (": accaScheduleId<id>;"). djsc --delete matches the pattern as a sed substring/regex,
        // so the un-delimited ": accaScheduleId1" also matched ": accaScheduleId10;", "...11;",
        // "...100;" -> deleting/editing one schedule silently wiped every sibling sharing the prefix.
        return delete(": accaScheduleId$id;")
    }

    suspend fun delete(schedule: DjsSchedule): Boolean {
        return deleteById(schedule.scheduleProfileId)
    }

    suspend fun stop(): Boolean
}

/**
 * Explicit, exhaustive outcome of a DJS install attempt. Replaces the old `Shell.Result?`
 * return whose null/exit-code was ambiguous: a null could mean "busybox missing", "shell
 * threw", OR "installed but version probe lost the daemon race" -- all collapsed to the same
 * generic failure dialog (and `onBusyboxMissing()` became dead code). Each real case now maps
 * to exactly one listener call. `result` carries the install Shell.Result (when any) so the UI
 * can still offer the install log on failure.
 */
data class DjsInstallOutcome(
    val success: Boolean,
    val busyboxMissing: Boolean,
    val result: Shell.Result?
)

object Djs {
    const val bundledVersion = 202108260

    /*
    * This method returns the name of the package with a compatible AccInterface
    * Note: there won't be a package per version. There will be a package for every uncompatible version
    * Ex: if releases from 201903071->201907211 are all compatible there will only be a package, but if a new release is incompatible a new package is created
    * */
    private fun getDjsInterfaceForversion(v: Int): DjsInterface {
        return when {
            v >= 202107280 -> mattecarra.accapp.djs.v202107280.DjsHandler()
            else           -> mattecarra.accapp.djs.legacy.DjsHandler()
        }
    }

    @Volatile
    private var INSTANCE: DjsInterface? = null

    val instance: DjsInterface
        get() {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }

            synchronized(this) {
                // Double-checked: a second thread that blocked on the lock must reuse the
                // instance the first one created instead of running getDjsVersion()'s shell again.
                return INSTANCE ?: createDjsInstance()
            }
        }

    private fun createDjsInstance(version: Int = getDjsVersion() ?: bundledVersion): DjsInterface {
        INSTANCE = getDjsInterfaceForversion(version)
        return INSTANCE as DjsInterface
    }

    fun isDjsInstalled(installationDir: File): Boolean {
        // Crash-safe: may be called on the main thread (nav/settings) before root is granted;
        // a libsu exception must never propagate.
        return try {
            Shell.su("test -f ${File(installationDir, "djs/service.sh").absolutePath}").exec().isSuccess
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun initDjs(installationDir: File): Boolean {
        return try {
            if (isDjsInstalled(installationDir))
                Shell.su("[ -f /dev/.vr25/djs/djsc ] || ${File(installationDir, "djs/service.sh").absolutePath}").exec().isSuccess
            else
                false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isInstalledDjsOutdated(): Boolean {
        // getDjsVersion() is null only when DJS is genuinely undetectable -> treat as outdated
        // (forces (re)install) rather than crashing.
        return getDjsVersion()?.let { it < bundledVersion } ?: true
    }

    suspend fun installBundledAccModule(context: Context): DjsInstallOutcome = withContext(Dispatchers.IO) {
        try {
            val bundleFile = File(context.filesDir, "djs_bundle.tar.gz")

            context.resources.openRawResource(R.raw.djs_bundle).use { out ->
                FileOutputStream(bundleFile).use {
                    out.copyTo(it)
                }
            }

            installLocalDjsModule(context)
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            DjsInstallOutcome(success = false, busyboxMissing = false, result = null)
        }
    }

    /*
    * This function assumes that djs tar gz is already in place
    */
    private suspend fun installLocalDjsModule(context: Context): DjsInstallOutcome = withContext(Dispatchers.IO){
        try {
            val installShFile = File(context.filesDir, "install-tarball.sh")

            context.resources.openRawResource(R.raw.install).use { installer ->
                FileOutputStream(installShFile).use {
                    installer.copyTo(it)
                }
            }

            val res = Shell.su("sh ${installShFile.absolutePath} djs").exec()

            // Busybox missing: the installer (install-tarball.sh / install.sh) exits 3. Surface
            // this explicitly so the busybox dialog can fire. The OLD code probed the version
            // first, threw, and returned null -> code==3 was lost and onBusyboxMissing() was
            // unreachable dead code.
            if (res.code == 3)
                return@withContext DjsInstallOutcome(success = false, busyboxMissing = true, result = res)

            // KernelSU/APatch: the installer raw-copies into /data/adb/modules; ksud-installed
            // modules are relabeled system_file. Mirror that (no-op under Magisk and on ROMs
            // whose type transition already labels correctly) so the module dir always looks
            // exactly like a manager-installed one to the boot-time module pass.
            try {
                Shell.su("[ -d /data/adb/magisk ] || { chcon -R u:object_r:system_file:s0 /data/adb/modules/djs 2>/dev/null; chmod 0755 /data/adb/modules/djs; }").exec()
            } catch (_: java.lang.Exception) {}

            // service.sh is fire-and-forget (`(djs.sh &) &`); the /dev/.vr25/djs/* runtime
            // symlinks (incl. djs-version) are created ASYNCHRONOUSLY by the backgrounded daemon.
            // The installer also early-exits 0 on an equal/newer already-installed version WITHOUT
            // restarting the daemon. Nudge the daemon so the runtime links exist for later use.
            try {
                Shell.su("[ -f /dev/.vr25/djs/djsc ] || sh /data/adb/vr25/djs/service.sh").exec()
            } catch (_: java.lang.Exception) {}

            // Verify the install with a signal that does NOT depend on the async daemon:
            // getDjsVersion() falls back to reading versionCode from module.prop, which install.sh
            // writes synchronously to /data/adb/vr25/djs/. This makes success detection race-free.
            // The short retry only covers a transient FS/SELinux settle; the common path succeeds
            // on the first probe.
            var version = getDjsVersion()
            var tries = 0
            while (version == null && tries < 10) {
                delay(300)
                version = getDjsVersion()
                tries++
            }

            if (version != null) {
                createDjsInstance(version)
                DjsInstallOutcome(success = true, busyboxMissing = false, result = res)
            } else {
                // Install command returned but DJS is not actually usable. Never claim success,
                // never swallow it silently: keep `res` so the UI can offer the install log.
                DjsInstallOutcome(success = false, busyboxMissing = false, result = res)
            }
        } catch (ex: java.lang.Exception) {
            ex.printStackTrace()
            DjsInstallOutcome(success = false, busyboxMissing = false, result = null)
        }
    }

    suspend fun uninstallDjs(installationDir: File): Shell.Result? = withContext(Dispatchers.IO) {
        try {
            Shell.su("sh ${File(installationDir, "djs/uninstall.sh").absolutePath}").exec()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Detect the installed DJS versionCode. Mirrors Acc.getAccVersion()'s multi-path fallback so
     * AccA handles a slow/absent daemon and any DJS version without ever throwing or returning a
     * misleading null:
     *   1. /dev/.vr25/djs/djs-version  (runtime symlink; absent until the daemon has started)
     *   2. djs-version                 (on PATH once the system is rebooted)
     *   3. /data/adb/vr25/djs/module.prop versionCode (CANONICAL, written synchronously by
     *      install.sh -> not subject to the daemon race; this is what makes install verification
     *      reliable)
     * Each probe is independently try/caught; a libsu exception in any of them yields null, not a
     * crash. The numeric regex tolerates the leading/trailing blank lines djs-version.sh prints.
     */
    private fun getDjsVersion(): Int? {
        return getDjsVersionViaShell("/dev/.vr25/djs/djs-version")
            ?: getDjsVersionViaShell("djs-version")
            ?: getDjsVersionFromModuleProp()
    }

    private fun getDjsVersionViaShell(cmd: String): Int? {
        return try {
            val out = Shell.su(cmd).exec().out.joinToString(separator = "\n")
            Regex("""\d{6,}""").find(out)?.value?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun getDjsVersionFromModuleProp(): Int? {
        return try {
            Shell.su("grep -m1 '^versionCode=' /data/adb/vr25/djs/module.prop")
                .exec().out.joinToString(separator = "\n")
                .substringAfter("versionCode=", "").trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
