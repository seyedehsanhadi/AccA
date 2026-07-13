package mattecarra.accapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import mattecarra.accapp.Preferences
import mattecarra.accapp.utils.UpdateNotifier

/**
 * Fires when the user swipes away the update notification. Records the versions that were on offer
 * so the same update is not shown again; a newer version (different tag / higher code) still is.
 */
class UpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateNotifier.ACTION_DISMISS) return
        val prefs = Preferences(context)
        intent.getStringExtra(UpdateNotifier.EXTRA_ACCA_VER)
            ?.takeIf { it.isNotBlank() }?.let { prefs.dismissedAccaVersion = it }
        val code = intent.getIntExtra(UpdateNotifier.EXTRA_ACC_CODE, 0)
        if (code > 0) prefs.dismissedAccVersionCode = code
    }
}
