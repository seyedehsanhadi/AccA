package mattecarra.accapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import mattecarra.accapp.Preferences
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.services.ChargeMeterService
import mattecarra.accapp.utils.LogExt

/**
 * Manifest receiver that keeps the status-bar charge meter in sync with the plug state even when
 * the app is not running, and revives a dead accd on plug-in.
 */
class ChargeMeterPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" ->
                try { ChargeMeterService.sync(context) } catch (_: Exception) {}
        }
        // Daemon guard (field report: Redmi Note 9S stuck at 4% plugged all night). Android's
        // phantom-process killer SIGKILLs accd - every exit trap is skipped, whatever charge cut
        // was engaged stays engaged, and nothing restarts the daemon until the user manually runs
        // acc. The moment that matters is exactly this broadcast: the user just plugged in and
        // expects ACC to manage the charge. Zero polling - this only ever runs on a plug event,
        // does nothing when the daemon is alive, and respects a DELIBERATE user stop.
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            val pending = goAsync()
            Thread {
                try {
                    val prefs = Preferences(context)
                    if (Shell.rootAccess()) {
                        runBlocking {
                            if (!prefs.accdUserStopped && !Acc.instance.isAccdRunning()) {
                                LogExt().d("ChargeMeterPowerReceiver", "accd dead on plug-in; restarting")
                                Acc.instance.abcStartDaemon()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogExt().e("ChargeMeterPowerReceiver", "daemon guard failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
