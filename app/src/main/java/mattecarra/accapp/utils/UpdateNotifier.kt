package mattecarra.accapp.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.activities.MainActivity
import mattecarra.accapp.receivers.UpdateDismissReceiver

/**
 * Checks GitHub for newer ACC and AccA versions and, when the "update notifications" setting is on,
 * shows ONE combined, persistent notification listing whatever has an update - with a per-component
 * "Update" action and the changelog. Neither component's update can hide the other's. Swiping the
 * notification records the offered versions so the same update never nags again (a newer one does).
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "acca_updates"
    private const val NOTIF_ID = 4721
    const val ACTION_DISMISS = "mattecarra.accapp.UPDATE_DISMISSED"
    const val EXTRA_ACCA_VER = "acca_ver"
    const val EXTRA_ACC_CODE = "acc_code"

    suspend fun checkAndNotify(context: Context) = withContext(Dispatchers.IO) {
        try {
            val prefs = Preferences(context)
            if (!prefs.updateNotifications) { cancel(context); return@withContext }
            val includePre = prefs.includePreReleases

            val accaInfo = GithubUtils.getLatestAccaReleaseInfo(includePre)
            val curAcca = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
            val accaLatest = accaInfo?.version?.trim()?.trimStart('v', 'V').orEmpty()
            val accaUpd = accaInfo != null && accaLatest.isNotBlank() && curAcca.isNotBlank() &&
                    VersionCompare.isNewer(accaLatest, curAcca) && accaLatest != prefs.dismissedAccaVersion

            val accCur = Acc.getAccVersion()   // null when ACC is not installed
            val accInfo = if (accCur != null) GithubUtils.getLatestAccModuleInfo(includePre) else null
            val accUpd = accInfo != null && accCur != null && accInfo.versionCode > accCur &&
                    accInfo.versionCode != prefs.dismissedAccVersionCode

            if (!accaUpd && !accUpd) { cancel(context); return@withContext }
            post(context, accaInfo?.takeIf { accaUpd }, accInfo?.takeIf { accUpd })
        } catch (e: Exception) {
            LogExt().e("UpdateNotifier", "checkAndNotify failed: ${e.message}")
        }
    }

    private fun clip(s: String?, n: Int): String {
        val t = s?.trim().orEmpty()
        return if (t.length > n) t.substring(0, n).trimEnd() + "…" else t
    }

    private fun post(context: Context, acca: ReleaseInfo?, acc: AccModuleInfo?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(context, nm)

        val accaVer = acca?.version?.trim()?.trimStart('v', 'V').orEmpty()
        val accVer = acc?.version?.trim()?.trimStart('v', 'V').orEmpty()

        val parts = mutableListOf<String>()
        if (acca != null) parts.add("AccA $accaVer")
        if (acc != null) parts.add("ACC $accVer")
        val content = parts.joinToString(context.getString(R.string.update_and))

        val big = StringBuilder(content)
        if ((acca?.notes?.isNotBlank() == true) || (acc?.notes?.isNotBlank() == true)) {
            big.append("\n\n").append(context.getString(R.string.update_whats_new))
            if (acca != null && acca.notes.isNotBlank()) big.append("\n\nAccA $accaVer:\n").append(clip(acca.notes, 700))
            if (acc != null && acc.notes.isNotBlank()) big.append("\n\nACC $accVer:\n").append(clip(acc.notes, 700))
        }

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )
        val delPi = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, UpdateDismissReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_ACCA_VER, if (acca != null) accaVer else "")
                .putExtra(EXTRA_ACC_CODE, acc?.versionCode ?: 0),
            piFlags()
        )

        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_info)
            .setContentTitle(context.getString(R.string.app_update_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big.toString()))
            .setContentIntent(openPi)
            .setDeleteIntent(delPi)
            .setAutoCancel(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (acca != null) {
            val url = acca.apkUrl?.takeIf { it.isNotBlank() } ?: acca.pageUrl
            b.addAction(0, context.getString(R.string.update_action_acca), viewPi(context, 2, url))
        }
        if (acc != null) {
            b.addAction(0, context.getString(R.string.update_action_acc), viewPi(context, 3, acc.releasePage))
        }

        nm.notify(NOTIF_ID, b.build())
    }

    private fun viewPi(context: Context, req: Int, url: String): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
