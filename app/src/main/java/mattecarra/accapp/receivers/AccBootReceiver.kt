package mattecarra.accapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.topjohnwu.superuser.Shell
import mattecarra.accapp.Preferences
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.djs.Djs

class AccBootReceiver: BroadcastReceiver() {
    private val LOG_TAG = "AccBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        // Some OEMs (and "fast boot" / HTC) only send a QUICKBOOT_POWERON broadcast and never
        // ACTION_BOOT_COMPLETED, so init has to run for those too or the daemon won't start.
        // MY_PACKAGE_REPLACED (an app update - Play Store, sideload, or AccA's own updater) kills
        // this process the same way a reboot does, but was never in this filter: the charge meter
        // and accd silently stayed dead after every AccA update until the next full reboot or a
        // plug/unplug cycle (ChargeMeterPowerReceiver's ACTION_POWER_CONNECTED/DISCONNECTED still
        // caught it eventually - just not right away, and not at all for someone who updates while
        // already charging and doesn't unplug for hours).
        val action = intent.action

        // LOCKED_BOOT_COMPLETED (directBootAware): fires BEFORE the first unlock on FBE phones.
        // Credential-encrypted storage (SharedPreferences, filesDir) is still locked here, so this
        // branch must not touch Preferences or app files. Both daemons live in /data/adb (device
        // storage, readable by root at boot) and their init is idempotent, so start them from pure
        // filesystem checks. This is what makes ACC settings apply on reboot WITHOUT waiting for
        // the user to unlock (the "settings do not apply until unlocked" report). The later
        // BOOT_COMPLETED branch then no-ops for anything already running.
        if ("android.intent.action.LOCKED_BOOT_COMPLETED" == action) {
            val pendingResult = goAsync()
            Thread {
                try {
                    if (Shell.rootAccess()) {
                        Shell.su("[ -e /dev/.vr25/acc/acca ] || { [ -f /data/adb/vr25/acc/service.sh ] && sh /data/adb/vr25/acc/service.sh; } || true").exec()
                        Shell.su("[ -f /dev/.vr25/djs/djsc ] || { [ -f /data/adb/vr25/djs/service.sh ] && sh /data/adb/vr25/djs/service.sh; } || true").exec()
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "locked-boot init failed: $e")
                } finally {
                    pendingResult.finish()
                }
            }.start()
            return
        }

        if (Intent.ACTION_BOOT_COMPLETED == action
            || "android.intent.action.QUICKBOOT_POWERON" == action
            || "com.htc.intent.action.QUICKBOOT_POWERON" == action
            || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            // Restore the status-bar charge meter on the MAIN thread, synchronously, right here in
            // onReceive: BOOT_COMPLETED is an allowed window to start a foreground service, and
            // starting it from the background worker thread below can fall outside that window on
            // Android 12+. It needs no root, so it must not wait on Shell.rootAccess(). sync()
            // checks enabled + plug + "always" and no-ops otherwise.
            try { mattecarra.accapp.services.ChargeMeterService.sync(context) } catch (_: Exception) {}

            // Shell.rootAccess() and the daemon init below are blocking root calls. Running them
            // directly here would block the main thread during the boot broadcast and ANR.
            // Hand off to a background thread and keep the broadcast alive via goAsync().
            val pendingResult = goAsync()
            Thread {
                try {
                    val preferences = Preferences(context)

                    if (Shell.rootAccess()) {
                        val accInitResult = Acc.initAcc(context.filesDir)
                        Log.d(LOG_TAG, "Acc deamon init. Success=$accInitResult")

                        if (preferences.djsEnabled) {
                            val djsInitResult = Djs.initDjs(context.filesDir)
                            Log.d(LOG_TAG, "DJS deamon init. Success=$djsInitResult")
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}