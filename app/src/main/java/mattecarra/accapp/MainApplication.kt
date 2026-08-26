package mattecarra.accapp

import android.annotation.SuppressLint
import android.app.Application
import androidx.multidex.MultiDexApplication
import androidx.preference.PreferenceManager.getDefaultSharedPreferences
import com.topjohnwu.superuser.Shell
import mattecarra.accapp.utils.LogExt

class MainApplication: MultiDexApplication()
{
    companion object
    {
        var mDEBUG: Int = 0

        /**
         * The app's own files directory, captured once at process start.
         *
         * Acc.kt hardcoded "/data/data/mattecarra.accapp/files", which is only correct for the
         * primary user of an unmodified install. A clone, a parallel-space copy or a secondary
         * user gets /data/user/<id>/mattecarra.accapp/files, so the hardcoded path does not exist
         * there and the app-managed ACC fallback is invisible: isAccInstalled() says no and the
         * daemon is never started from it. Acc is a singleton with a Context-free getter, hence
         * the static capture here rather than threading a Context through.
         *
         * Null until onCreate has run; every caller keeps the old literal as its fallback, so the
         * primary-user path is byte-for-byte what it was.
         */
        @Volatile var filesDirPath: String? = null

        init
        {
            Shell.Config.setFlags(Shell.FLAG_REDIRECT_STDERR)
            Shell.Config.verboseLogging(BuildConfig.DEBUG)
            Shell.Config.setTimeout(10)
        }
    }

    @SuppressLint("LogNotTimber")
    override fun onCreate()
    {
        super.onCreate()
        // Before anything that might read it, and outside the try below: filesDir is a plain path
        // lookup and cannot throw the FBE exception the preference read can.
        filesDirPath = try { filesDir?.absolutePath } catch (e: Exception) { null }
        // toIntOrNull: a non-numeric "appdebug" pref (corruption, restored backup,
        // ListPreference edge) must not crash the whole app on launch.
        // try/catch: on FBE (file-based-encryption) devices the directBootAware boot receiver
        // starts this process BEFORE the first unlock, and credential-encrypted SharedPreferences
        // throw IllegalStateException there. That killed the whole app on every reboot -- which
        // also took down the early daemon start the locked-boot branch exists to perform.
        // Nothing here is essential to correctness, so degrade to defaults instead of dying.
        try {
            mDEBUG = (getDefaultSharedPreferences(applicationContext).getString("appdebug", "0") ?: "0").toIntOrNull() ?: 0
            LogExt().s(javaClass.simpleName, "DEBUG=$mDEBUG " +when(mDEBUG) {0->"[NONE]" 1->"[CONSOLE]" 2->"[FILE]" else->"[UNKNOWN]"})
        } catch (e: Exception) {
            mDEBUG = 0
        }
    }
}