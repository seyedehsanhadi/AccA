package mattecarra.accapp.utils

import android.content.Context
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc

object Acca117RelockMigration {
    private const val TAG = "Acca117Relock"
    private const val PREF_KEY = "acca_v117_relock_done"

    /**
     * One-shot 1.1.7 migration. Up to 1.1.6 the "Automatically cycle through switches" toggle
     * wrote charging_switch WITHOUT the trailing " --" lock marker, leaving the daemon in
     * auto-mode (which has an enforcement gap during cycle_switches_off). 1.1.7 removed the
     * toggle and always re-locks on save, but a user who had the toggle ON in 1.1.6 still has
     * the saved config in auto-mode after upgrading until they save a new config in the editor.
     *
     * On FIRST LAUNCH of 1.1.7 (per the [PREF_KEY] flag), read the live daemon config and, if
     * the charging_switch line is non-empty and lacks " --", re-write it WITH " --" via the
     * existing handler command builder (which hardcodes the suffix). Show a one-time toast so
     * power users know it happened. No-op on every subsequent launch.
     *
     * Caller contract: invoke ONLY after root + ACC have been confirmed available (otherwise
     * `acca` calls fail). Runs the shell work on [Dispatchers.IO] and hops to Main only for the
     * toast. Never throws -- a shell/parse failure leaves the flag UNSET so the next launch
     * retries (the action is idempotent).
     */
    suspend fun maybeRelockSwitchOnceForV117(context: Context) = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_KEY, false)) return@withContext
        try {
            val handler = Acc.instance
            // Match the parsing path used by readConfigToString() in v202107280.AccHandler
            // (`acca --set --print`). Reading the live daemon config -- not the on-disk file --
            // is what catches the 1.1.6-saved auto-mode state.
            val res = Shell.su("/dev/.vr25/acc/acca --set --print").exec()
            if (!res.isSuccess) {
                LogExt().w(TAG, "acca --set --print failed (code=${res.code}); will retry next launch")
                return@withContext
            }
            val config = res.out.joinToString(separator = "\n")
            val sw = handler.getCurrentChargingSwitch(config)
            if (sw.isNullOrBlank()) {
                // Empty switch = automatic discovery; nothing to force. Mark done so we don't probe again.
                prefs.edit().putBoolean(PREF_KEY, true).apply()
                return@withContext
            }
            // getCurrentChargingSwitch strips the " --" marker, so re-check the raw line via the
            // handler's own isAutomaticSwitchEnabled (which inspects the charging_switch value
            // alone, not the whole config -- the same fix that landed for the dashboard read).
            val automatic = handler.isAutomaticSwitchEnabled(config)
            if (!automatic) {
                // Already locked. Mark done.
                prefs.edit().putBoolean(PREF_KEY, true).apply()
                return@withContext
            }
            // Re-write WITH " --". The v202107280 builder hardcodes the suffix regardless of
            // the automaticSwitchingEnabled flag (rc6 change), so passing false is correct and
            // future-proof if the flag is ever wired back up.
            val ok = Shell.su(handler.getUpdateAccChargingSwitchCommand(sw, false)).exec().isSuccess
            if (ok) {
                prefs.edit().putBoolean(PREF_KEY, true).apply()
                withContext(Dispatchers.Main) {
                    try {
                        Toast.makeText(context, R.string.relock_v117_done, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) { /* finishing activity / no window -- silent */ }
                }
                LogExt().s(TAG, "Re-locked charging_switch=\"$sw --\" (one-shot 1.1.7 migration)")
            } else {
                LogExt().w(TAG, "Re-lock shell command failed; will retry next launch")
            }
        } catch (e: Exception) {
            // Don't break first launch on a parse / shell error. Leaving the flag unset means
            // we'll try again next time the user opens the app.
            LogExt().e(TAG, "migration failed: ${e.message}")
        }
    }
}
