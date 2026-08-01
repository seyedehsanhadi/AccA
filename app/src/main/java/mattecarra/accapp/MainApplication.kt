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