package mattecarra.accapp.acc

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mattecarra.accapp.CurrentUnit
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.VoltageUnit
import mattecarra.accapp.acc._interface.AccInterface
import mattecarra.accapp.utils.LogExt
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.abs

object Acc {
    // Fallback ACC API version for picking a handler when the installed version can't be read yet
    // (e.g. right after a flash, before the daemon is up). AccA ships NO bundle; keep this at the
    // latest known release so a fresh/unreadable install uses the newest handler.
    const val fallbackVersion = 202505300
    private const val TAG = "Acc"
    private val FILES_DIR = "/data/data/mattecarra.accapp/files"

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

    // Force the charger to re-negotiate the fast-charge contract (AICL/APSD) and un-latch any
    // stray cut, so fast charging re-engages after it dropped (bad cable seat, thermal that
    // cleared, a stray app). ENABLE direction only -- it can never overcharge or stop charging;
    // ACC re-applies your limit on its next loop, so this is a best-effort boost, not a bypass of
    // your settings. Crash-safe: a libsu failure returns false, never propagates.
    suspend fun rekickFastCharge(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.su(
                "cd /sys/class/power_supply 2>/dev/null || exit 1; " +
                "for f in */input_suspend */charge_disable */batt_slate_mode */op_disable_charge */disable_charging; do [ -w \"\$f\" ] && echo 0 2>/dev/null > \"\$f\"; done; " +
                "for f in */charging_enabled */battery_charging_enabled */charge_enabled */charging_enable */enable_charging */enable_charger; do [ -w \"\$f\" ] && echo 1 2>/dev/null > \"\$f\"; done; " +
                "for f in */apsd_rerun */rerun_aicl; do [ -w \"\$f\" ] && echo 1 2>/dev/null > \"\$f\"; done; " +
                "[ -w /proc/mtk_battery_cmd/en_power_path ] && echo 1 2>/dev/null > /proc/mtk_battery_cmd/en_power_path; true"
            ).exec().isSuccess
        } catch (e: Exception) {
            LogExt().e(TAG, "rekickFastCharge failed: ${Log.getStackTraceString(e)}")
            false
        }
    }

    // AccA no longer installs or bundles ACC. ACC is a Magisk/KernelSU/APatch module the user
    // flashes themselves; AccA only detects it (isAccInstalled) and, when it is missing or
    // outdated, points the user at the GitHub release to download + flash (SettingsFragment /
    // showAccNotFound). This removes the "AccA reinstalls its bundled ACC over your flashed one"
    // override class entirely.

    private suspend fun calibrateMeasurements(context: Context) = withContext(Dispatchers.IO) {

        var microVolts = 0
        var microAmpere = 0

        for (i in 0..10) {
            val batteryInfo = Acc.instance.getBatteryInfo()
            if(batteryInfo.getRawVoltageNow() > 1000000) microVolts++
            if(abs(batteryInfo.getRawCurrentNow()) > 10000) microAmpere++
            delay(250)
        }

        val preferences = Preferences(context)
        preferences.currentInputUnitOfMeasure = if(microAmpere >= 6) CurrentUnit.uA else CurrentUnit.mA
        preferences.voltageInputUnitOfMeasure = if(microVolts >= 6)  VoltageUnit.uV else VoltageUnit.mV
    }

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
