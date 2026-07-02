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
    const val bundledVersion = 202505245
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

    internal fun createAccInstance(version: Int = getAccVersion() ?: bundledVersion): AccInterface{
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

    fun isInstalledAccOutdated(): Boolean = runBlocking {
        instance.getAccVersion()?.let { it < bundledVersion } ?: true
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

    suspend fun installBundledAccModule(context: Context): Shell.Result?  = withContext(Dispatchers.IO) {
        try {
            val bundleFile = File(context.filesDir, "acc_bundle.tar.gz")

            context.resources.openRawResource(R.raw.acc_bundle).use { out ->
                FileOutputStream(bundleFile).use {
                    out.copyTo(it)
                }
            }

            installLocalAccModule(context)
        } catch (ex: java.lang.Exception) {
            LogExt().e(TAG, "installBundledAccModule failed: ${Log.getStackTraceString(ex)}")
            null
        }
    }

    suspend fun installAccModuleVersion(context: Context, version: String): Shell.Result?  = withContext(Dispatchers.IO) {
        try {
            val bundleFile = File(context.filesDir, "acc_bundle.tar.gz")

            BufferedInputStream(URL("https://github.com/seyedehsanhadi/acc/archive/$version.tar.gz").openStream())
                .use { inStream ->
                    FileOutputStream(bundleFile)
                        .use {
                            inStream.copyTo(it)
                        }
                }

            installLocalAccModule(context)
        } catch (ex: java.lang.Exception) {
            LogExt().e(TAG, "installAccModuleVersion failed: ${Log.getStackTraceString(ex)}")
            null
        }
    }

    /*
    * This function assumes that acc tar gz is already in place
    */
    private suspend fun installLocalAccModule(context: Context): Shell.Result? = withContext(Dispatchers.IO){
        try {
            val installShFile = File(context.filesDir, "install-tarball.sh")

            context.resources.openRawResource(R.raw.install).use { installer ->
                FileOutputStream(installShFile).use {
                    installer.copyTo(it)
                }
            }

            val res = Shell.su("sh ${installShFile.absolutePath} acc").exec()

            try {
                Shell.su("[ -d /data/adb/magisk ] || { chcon -R u:object_r:system_file:s0 /data/adb/modules/acc 2>/dev/null; chmod 0755 /data/adb/modules/acc; }").exec()
            } catch (_: java.lang.Exception) {}

            val version = getAccVersion() ?: throw java.lang.Exception("ACC installation failed")

            createAccInstance()

            if(version >= 202002292) {
                val preferences = Preferences(context)
                preferences.currentInputUnitOfMeasure = CurrentUnit.A
                preferences.voltageInputUnitOfMeasure = VoltageUnit.V
            } else if(version >= 202002290) {
                val preferences = Preferences(context)
                preferences.currentInputUnitOfMeasure = CurrentUnit.mA
                preferences.voltageInputUnitOfMeasure = VoltageUnit.V
            } else {
                calibrateMeasurements(context)
            }

            res
        } catch (ex: java.lang.Exception) {
            LogExt().e(TAG, "installLocalAccModule failed: ${Log.getStackTraceString(ex)}")
            null
        }
    }

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

    private fun getAccVersion(): Int? {
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
    // null -> AccA would use bundledVersion's handler and mis-parse an OLDER installed
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
