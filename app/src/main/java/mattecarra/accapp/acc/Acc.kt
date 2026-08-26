package mattecarra.accapp.acc

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc._interface.AccInterface
import mattecarra.accapp.MainApplication
import mattecarra.accapp.utils.LogExt
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object Acc {
    // Fallback ACC API version for picking a handler when the installed version can't be read yet
    // (e.g. right after a flash, before the daemon is up). AccA ships NO bundle; keep this at the
    // latest known release so a fresh/unreadable install uses the newest handler.
    const val fallbackVersion = 202505333
    private const val TAG = "Acc"
    // The literal is the primary-user path and stays as the fallback; the real directory is asked
    // for when the app has started. A clone, parallel-space copy or secondary user lives under
    // /data/user/<id>/..., where the literal does not exist -- so isAccInstalled() returned false
    // and the app-managed ACC install could never be started from the app. Flashed-module installs
    // were unaffected either way, because initAcc prefers /data/adb/vr25/acc/service.sh.
    private val FILES_DIR: String
        get() = MainApplication.filesDirPath ?: "/data/data/mattecarra.accapp/files"

    /*
    * This method returns the name of the package with a compatible AccInterface
    * Note: there won't be a package per version. There will be a package for every uncompatible version
    * Ex: if releases from 201903071->201907211 are all compatible there will only be a package, but if a new release is incompatible a new package is created
    * */
    private fun getAccInterfaceForversion(v: Int): AccInterface {
        return when {
            v >= 202107280 -> mattecarra.accapp.acc.v202107280.AccHandler(v)
            v >= 202007220 -> mattecarra.accapp.acc.v202107280.AccHandler(v)
            v >= 202007030 -> mattecarra.accapp.acc.v202007030.AccHandler(v)
            v >= 202006140 -> mattecarra.accapp.acc.v202006140.AccHandler(v)
            v >= 202002290 -> mattecarra.accapp.acc.v202002290.AccHandler(v)
            v >= 202002170 -> mattecarra.accapp.acc.v202002170.AccHandler(v)
            v >= 201910130 -> mattecarra.accapp.acc.v201910132.AccHandler(v)
            v >= 201903071 -> mattecarra.accapp.acc.v201903071.AccHandler(v)
            else           -> mattecarra.accapp.acc.legacy.AccHandler(v)/* This is used for all the versions before v20190371*/
        }
    }

    @Volatile
    private var INSTANCE: AccInterface? = null

    val instance: AccInterface
        get() {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }

            synchronized(this) {
                // Create acc instance here
                initAcc(File(FILES_DIR))
                return createAccInstance()
            }
        }

    internal fun createAccInstance(version: Int = getAccVersion() ?: fallbackVersion): AccInterface{
        INSTANCE = getAccInterfaceForversion(version)
        return INSTANCE as AccInterface
    }

    fun isAccInstalled(installationDir: File): Boolean {
        // ACC is usable if it is RUNNING now (/dev/.vr25/acc/acca), OR present at the
        // canonical exec home /data/adb/vr25/acc/service.sh -- a real dir for a
        // non-Magisk install and a symlink to the module dir for Magisk/KSU/APatch
        // (install.sh: `ln -sf $installDir /data/adb/$domain/`), OR at the legacy
        // app-managed location. Before rc17 only the last was checked, so a
        // SEPARATELY-FLASHED ACC module read as "not installed" and AccA showed
        // "ACC module not found" even though acca was installed and running.
        val appService = File(installationDir, "acc/service.sh").absolutePath
        // Crash-safe: this can run at startup (Acc.instance) before root is granted, so a
        // thrown libsu exception (no shell, I/O error) must never propagate. Treat any
        // failure as "not installed" (safe default).
        return try {
            Shell.su(
                "test -e /dev/.vr25/acc/acca || test -f /data/adb/vr25/acc/service.sh || test -f $appService"
            ).exec().isSuccess
        } catch (e: Exception) {
            LogExt().e(TAG, "isAccInstalled failed: ${Log.getStackTraceString(e)}")
            false
        }
    }

    fun initAcc(installationDir: File): Boolean {
        if (!isAccInstalled(installationDir)) return false
        // If the daemon/tool is not up yet, start ACC from whichever service.sh exists,
        // preferring the canonical exec home (flashed/standalone module) over the
        // legacy app-managed copy. Fixes a flashed module never being started by AccA.
        val appService = File(installationDir, "acc/service.sh").absolutePath
        // Crash-safe: runs at startup via the instance getter before root may be granted, so a
        // thrown libsu exception must never propagate. Treat a failed start as "not started".
        return try {
            Shell.su(
                "[ -e /dev/.vr25/acc/acca ] || " +
                "if [ -f /data/adb/vr25/acc/service.sh ]; then sh /data/adb/vr25/acc/service.sh; else sh $appService; fi"
            ).exec().isSuccess
        } catch (e: Exception) {
            LogExt().e(TAG, "initAcc failed: ${Log.getStackTraceString(e)}")
            false
        }
    }

    // The manual fast-charge re-kick was REMOVED.
    //
    // It wrote apsd_rerun and rerun_aicl straight to the nodes. ACC gained a guard in rc22 that
    // refuses to re-run charger detection while a working high-voltage contract is live, because
    // apsd_rerun drops a QC or PD contract to the 5V floor and nothing in software brings it back -
    // only a physical replug does. This path never consulted that guard, so the app could tear down
    // a healthy 9V contract that the daemon itself would have refused to touch.
    //
    // A re-kick is a repair. ACC still performs it automatically when charging is genuinely stalled,
    // behind four gates: `acc -sk off`, a rate limit, a per-plug contract latch, and the 6V check.
    // That path is the supported one, and `acc -sk on|off` remains the user control.

    // AccA no longer installs or bundles ACC. ACC is a Magisk/KernelSU/APatch module the user
    // flashes themselves; AccA only detects it (isAccInstalled) and, when it is missing or
    // outdated, points the user at the GitHub release to download + flash (SettingsFragment /
    // showAccNotFound). This removes the "AccA reinstalls its bundled ACC over your flashed one"
    // override class entirely.

    // calibrateMeasurements() was removed here. It was never called, and against ACC
    // 2025.x `-i` output (volts "3.83", amps "-0.35") both of its thresholds failed on
    // every sample, so it would have set the input units to mV/mA and divided the whole
    // display by 1000. The V/A defaults in Preferences are the correct ones.

    internal fun getAccVersion(): Int? {
        // Crash-safe: this runs at startup (Acc.instance) before root may be granted, so a
        // thrown libsu exception (no shell, I/O error) must never propagate. split() never
        // returns an empty list, so last()/first() are safe; toIntOrNull() guards the parse.
        return (try {
            Shell.su("/dev/.vr25/acc/acc --version").exec().out.joinToString(separator = "\n").split("(").last().split(")").first().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }) ?: getAccVersionLegacy() ?: getAccVersionFromModuleProp()
    }

    // File-based fallback so AccA handles ANY ACC version. The running tool
    // (/dev/.vr25/acc/acc) may be absent even when ACC is installed (e.g. flashed
    // but the daemon has not started yet), which would make getAccVersion() return
    // null -> AccA would use fallbackVersion's handler and mis-parse an OLDER installed
    // ACC. Reading versionCode from the module prop at the canonical home (a symlink
    // to the module dir for Magisk/KSU) yields the REAL installed version regardless.
    private fun getAccVersionFromModuleProp(): Int? {
        return try {
            Shell.su("grep -m1 '^versionCode=' /data/adb/vr25/acc/module.prop")
                .exec().out.joinToString(separator = "\n")
                .substringAfter("versionCode=", "").trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun getAccVersionToStr(): String {
        // Use the absolute binary path (KSU has no `acc`/`/dev/acca` on PATH, so the bare
        // probe returned "" and the About screen showed a blank ACC version). Fall back to
        // the legacy bare path for any odd install where the canonical path is absent.
        return try {
            Shell.su("/dev/.vr25/acc/acca --version").exec().out.joinToString(separator = "\n").ifBlank {
                Shell.su("acc --version").exec().out.joinToString(separator = "\n")
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun getAccVersionLegacy(): Int? {
        return try {
            Shell.su("acc --version").exec().out.joinToString(separator = "\n").split("(").last().split(")").first().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
