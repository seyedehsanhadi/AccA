package mattecarra.accapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.activities.MainActivity
import mattecarra.accapp.utils.LogExt

/**
 * Status-bar charge meter (rc15). A persistent foreground service whose ongoing notification's
 * small icon is a live-rendered number (mA / W / both, per the user's setting), with the rc14
 * charge-speed line in the expanded view. 100% read-only: it displays telemetry, writes nothing.
 *
 * Current comes from Android's BatteryManager (NO root needed); the richer input watts + class
 * come from ACC's read-only `--state` when root is available, with a battery-side fallback so the
 * meter still works with no root and on any charger. Ticks only while it needs to (screen on and,
 * unless "always" is set, only while charging), so it costs almost nothing on battery.
 *
 * Start/stop is driven by [Preferences.chargeMeterEnabled] + a power/screen receiver; the service
 * stops itself when the feature is off, or when unplugged and "always" is off.
 */
class ChargeMeterService : Service() {

    companion object {
        // IMPORTANCE_DEFAULT (kept fully silent) is REQUIRED to get a status-bar strip icon;
        // IMPORTANCE_LOW/MIN only show in the shade, never in the strip. New channel id because a
        // channel's importance is immutable once created (the old "_low" one stayed LOW on testers).
        private const val CHANNEL_ID = "acca_charge_meter_bar"
        private const val NOTIF_ID = 4712
        const val ACTION_START = "acca.meter.start"
        // Internal idle-stop: sent by sync() when plug/screen conditions say "don't show right now"
        // (e.g. unplugged in charge-only mode). Transient - must NOT touch the enabled preference,
        // or the very next plug-in would find the feature silently turned off.
        const val ACTION_STOP = "acca.meter.stop"
        // The notification's own "Stop" button: an explicit user request to turn the feature off,
        // so THIS one clears the preference too (see onStartCommand).
        const val ACTION_USER_STOP = "acca.meter.user_stop"
        const val ACTION_REFRESH = "acca.meter.refresh"   // pref changed -> re-read + re-render
        private const val TICK_MS = 3000L                 // refresh cadence while screen on
        private const val ROOT_EVERY = 3                  // root --state only every 3rd tick (~9s)

        /** True if a charger is attached right now (sticky battery intent, no root). */
        fun isPluggedNow(context: Context): Boolean {
            val bs = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = (bs?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val status = bs?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

        /**
         * Start or stop the meter to match the master toggle, so it works even when the app is
         * closed (called from a manifest power receiver + boot + launch). Always on while enabled,
         * charging or on battery - the plug state only changes what the number reads, never whether
         * it shows.
         */
        fun sync(context: Context) {
            val p = Preferences(context)
            val shouldRun = p.chargeMeterEnabled
            val intent = Intent(context, ChargeMeterService::class.java)
                .setAction(if (shouldRun) ACTION_START else ACTION_STOP)
            try {
                if (shouldRun && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                LogExt().e("ChargeMeterService", "sync() failed: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Preferences
    private lateinit var bm: BatteryManager
    private var pm: android.os.PowerManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // Ground truth for "is the screen on", independent of whether a SCREEN_OFF broadcast arrived.
    private fun screenReallyOn(): Boolean = try { pm?.isInteractive ?: screenOn } catch (e: Exception) { screenOn }
    private var promoted = false
    private var receiver: BroadcastReceiver? = null

    // Live state, updated on each tick.
    private var plugged = false
    private var screenOn = true

    // Cached --state so we don't spawn a root shell every tick; refreshed once every ROOT_EVERY
    // ticks (class/watts change slowly). BatteryManager current is read fresh every tick (free).
    @Volatile private var lastState: mattecarra.accapp.models.AccState? = null
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            // Self-heal on every tick: refresh the plug state from ground truth (for the reading
            // itself, not for whether the meter shows) and bail out if the master toggle is off.
            // This is what makes it reliable even after a START_STICKY restart - the tick is the
            // one thing guaranteed to run while the meter is visible (screen on), so it owns the guard.
            plugged = isPluggedNow(this@ChargeMeterService)
            if (!prefs.chargeMeterEnabled) { stopMeter(); return }
            update(refreshState = (tickCount % ROOT_EVERY == 0))
            tickCount++
            // ZERO-WAKE design: this is a plain Handler on the main looper - it only fires while
            // the device is already awake, never via AlarmManager/wakelock, so it cannot wake the
            // phone or fight doze. Reschedule ONLY while the screen is on; screen-off removes the
            // callback entirely (see the receiver), so there is no background polling and no drain.
            if (screenReallyOn()) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        ensureChannel()
        registerStateReceiver()
        refreshPluggedScreen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote first: if we were launched via startForegroundService we MUST call
        // startForeground within ~5s or the system kills us.
        if (!promoted) {
            // First frame uses an instant rendered number (not the battery drawable), so the strip
            // never flashes a battery glyph on start or on a swipe re-post.
            val q = quickIcon()
            try {
                startForeground(NOTIF_ID, buildNotification(
                    getString(R.string.charge_meter_charging), null, null, q?.first, q?.second, prefs.chargeMeterStyle))
                promoted = true
            } catch (e: Exception) { LogExt().e(javaClass.simpleName, "startForeground failed: ${e.message}") }
        }
        when (intent?.action) {
            // User tapped "Stop" in the notification: turn the feature off so it does not come right
            // back on the next plug/screen event, unlike the plain internal ACTION_STOP below.
            ACTION_USER_STOP -> { prefs.chargeMeterEnabled = false; stopMeter(); return START_NOT_STICKY }
            ACTION_STOP -> { stopMeter(); return START_NOT_STICKY }
            else -> {
                // Covers ACTION_START AND a START_STICKY restart with a null intent. Re-check the
                // master toggle: the OS could restart this service after the user already turned
                // it off, and this guard keeps that restart from resurrecting a dead meter.
                if (!shouldShow()) { stopMeter(); return START_NOT_STICKY }
                stopped = false          // fresh (re)start clears the stop guard
                scheduleTicks()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }

    // ---- lifecycle helpers ----

    @Volatile private var stopped = false

    private fun stopMeter() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        // Explicitly cancel so an in-flight tick coroutine can't leave a ghost notification (the
        // stale "battery icon" seen after turning the meter off).
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
              else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) {}
        stopSelf()
    }

    private fun shouldShow(): Boolean = prefs.chargeMeterEnabled

    private fun scheduleTicks() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun registerStateReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    // The meter always shows while enabled - a plug change only flips what the
                    // number reads. Reset the tick counter so the first frame after a plug change
                    // does a full (root) refresh, not a cached one.
                    Intent.ACTION_POWER_CONNECTED -> { plugged = true; tickCount = 0; scheduleTicks() }
                    Intent.ACTION_POWER_DISCONNECTED -> { plugged = false; tickCount = 0; scheduleTicks() }
                    // Screen ON re-arms ticking; screen OFF stops ALL work (no poll, no render, no
                    // root call) - the guarantee that the meter draws zero battery with the screen off.
                    Intent.ACTION_SCREEN_ON -> { screenOn = true; scheduleTicks() }
                    Intent.ACTION_SCREEN_OFF -> { screenOn = false; handler.removeCallbacks(tick) }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try { registerReceiver(receiver, f) } catch (e: Exception) { LogExt().e(javaClass.simpleName, "register failed: ${e.message}") }
    }

    // Battery level from the sticky intent (no root), as an icon fallback so the strip always has
    // a number instead of the plain battery glyph.
    private fun batteryLevelPct(): Int? {
        val bs = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val lvl = bs.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scl = bs.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (lvl >= 0 && scl > 0) lvl * 100 / scl else null
    }

    // Instant number for the FIRST frame (startForeground) and swipe re-posts, so the strip is
    // never the static battery glyph even for a moment. Synchronous, no root: BatteryManager
    // current (mA) if readable, else battery %. Sign by plug state.
    private fun quickIcon(): Pair<String, String>? {
        val curUa = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (e: Exception) { Long.MIN_VALUE }
        val maAbs = if (curUa == Long.MIN_VALUE || curUa == 0L) null else kotlin.math.abs(curUa / 1000L).toInt()
        val sign = if (plugged) "+" else "-"
        if (maAbs != null && maAbs > 5) return "$sign$maAbs" to "mA"
        val pct = batteryLevelPct()
        if (pct != null) return "$pct" to "%"
        return null
    }

    private fun refreshPluggedScreen() {
        val bs = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = bs?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        plugged = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
                (bs?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    // ---- the update: read telemetry, render, publish ----

    private fun update(refreshState: Boolean = true) {
        // Only the master toggle can stop the meter from inside a tick. Plug-state driven stops go
        // through the power receiver (a transient false read must not freeze/kill the notification).
        if (stopped) return
        if (!prefs.chargeMeterEnabled) { stopMeter(); return }
        scope.launch {
            // Battery current from BatteryManager (microamps, no root). Magnitude for the number;
            // sign tells charge (into battery) vs discharge (on battery, only shown in "always").
            val curUa = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (e: Exception) { Long.MIN_VALUE }
            val curMa: Int? = if (curUa == Long.MIN_VALUE) null else (curUa / 1000L).toInt()
            val maAbs = curMa?.let { kotlin.math.abs(it) }

            // Rich watts + class from ACC --state (read-only, root). Only re-read every ROOT_EVERY
            // ticks; reuse the cached value otherwise so we don't spawn a root shell every tick.
            if (refreshState) {
                val fresh = try { withContext(Dispatchers.IO) { Acc.instance.getState() } } catch (e: Exception) { null }
                if (fresh != null) lastState = fresh
            }
            val st = lastState
            val cls = st?.chargeClass
            val vin = st?.inputVoltageMv
            val iin = st?.inputCurrentMa
            val vbatMv = st?.voltageRaw?.let { if (it >= 100000L) (it / 1000L).toInt() else it.toInt() }

            // Power in tenths of a watt (one-decimal precision so a small charge shows 2.5W, not 0).
            // Prefer measured charger input (V x A); fall back to battery-side (V x A), which is <=
            // input power so it can only under-state. Null when nothing measurable.
            val wattsX10: Int? = when {
                vin != null && iin != null && vin > 1000 && kotlin.math.abs(iin) > 30 ->
                    (vin.toLong() * kotlin.math.abs(iin) / 100000L).toInt()
                maAbs != null && vbatMv != null && vbatMv > 1000 && maAbs > 30 ->
                    (vbatMv.toLong() * maAbs / 100000L).toInt()
                else -> null
            }
            fun wattsShade(x10: Int): String =
                if (x10 >= 100) "${x10 / 10} W" else "${x10 / 10}.${x10 % 10} W"
            fun wattsNum(x10: Int): String =
                if (x10 >= 100) "${x10 / 10}" else "${x10 / 10}.${x10 % 10}"   // no unit (unit line below)

            // Sign by plug state: "+" charging (into battery), "-" on battery (discharging). Plug
            // state is unambiguous across OEMs, unlike the raw current sign.
            val sign = if (plugged) "+" else "-"
            val display = prefs.chargeMeterDisplay
            // "acc" (default) = ACC's own reading, which reflects Capacity Mask if the user has one
            // configured. "system" = the raw OS battery level (BatteryManager), unaffected by any
            // mask - what the original AccA always showed. Same source for both places this meter
            // displays a percentage, so the icon and the shade detail line never disagree.
            val accPct = st?.capacityPct?.takeIf { it in 0..100 }
            val sysPct = batteryLevelPct()
            val battPct = if (prefs.chargeMeterBatterySource == "system") sysPct ?: accPct else accPct ?: sysPct

            // Pick ONE strip value + its unit, kept UNSIGNED and unit-less here; the sign and unit
            // are only appended below if the user opted in (the strip fits ~3 glyphs, so number-only
            // is the readable default - the shade carries the fully signed, united detail).
            //   AUTO: on battery -> mA. Charging -> W (>=1 W), else the small-current mA.
            //   Forced "w"/"ma": that unit whenever there's data.
            var stripNum: String? = null
            var stripUnit = ""
            when (display) {
                "ma" -> if (maAbs != null) { stripNum = "$maAbs"; stripUnit = "mA" }
                        else if (wattsX10 != null) { stripNum = wattsNum(wattsX10); stripUnit = "W" }
                "w" -> if (wattsX10 != null) { stripNum = wattsNum(wattsX10); stripUnit = "W" }
                       else if (maAbs != null) { stripNum = "$maAbs"; stripUnit = "mA" }
                else -> { // auto
                    if (!plugged) {
                        if (maAbs != null) { stripNum = "$maAbs"; stripUnit = "mA" }
                        else if (wattsX10 != null) { stripNum = wattsNum(wattsX10); stripUnit = "W" }
                    } else {
                        if (wattsX10 != null && wattsX10 >= 10) { stripNum = wattsNum(wattsX10); stripUnit = "W" }
                        else if (maAbs != null) { stripNum = "$maAbs"; stripUnit = "mA" }
                        else if (wattsX10 != null) { stripNum = wattsNum(wattsX10); stripUnit = "W" }
                    }
                }
            }
            // Never fall back to a plain battery icon while the meter is on: if no current/watts are
            // readable this instant, show the battery level so the strip always has a real number.
            if (stripNum == null && battPct != null) { stripNum = "$battPct"; stripUnit = "%" }

            // The strip always shows the sign and unit - both are permanent, not user toggles.
            val iconValue: String? = stripNum?.let { sign + it }
            val iconUnit: String? = stripUnit

            // ---- shade content ----
            // Title: class + both watts and amps (with sign), e.g. "Fast charge  ·  +19 W  ·  +1.95 A".
            val wStr = wattsX10?.let { "$sign${wattsShade(it)}" }
            val aStr = maAbs?.let { "$sign" + String.format("%.2f A", it / 1000f) }
            val numbers = listOfNotNull(wStr, aStr).joinToString("  ·  ")
            val classWord = if (!plugged) getString(R.string.charge_meter_on_battery) else getString(when (cls) {
                "slow" -> R.string.charge_class_slow; "standard" -> R.string.charge_class_standard
                "fast" -> R.string.charge_class_fast; "superfast" -> R.string.charge_class_superfast
                "hyper" -> R.string.charge_class_hyper; else -> R.string.charge_meter_charging
            })
            val title = listOf(classWord, numbers).filter { it.isNotBlank() }.joinToString("  ·  ")

            // Detailed dashboard lines (BigText). Charger, then battery, then the ACC limit.
            val vbatV = vbatMv?.let { String.format("%.2f", it / 1000f) }
            val chargerLine: String? = if (plugged && vin != null && vin > 1000) {
                val ratio = vbatMv?.takeIf { it > 1000 }?.let { String.format("x%.1f", (vin * 84 / it) / 100.0) }
                val vinS = String.format("%.1f V", vin / 1000f)
                val inA = iin?.let { String.format("%.2f A in", kotlin.math.abs(it) / 1000f) }
                listOfNotNull(vinS, inA, ratio?.let { "$it to battery" }).joinToString("  ·  ")
            } else null
            val battLine: String? = if (st != null && battPct != null && vbatV != null)
                getString(R.string.charge_meter_battery_line, battPct, vbatV, st.tempDeciC / 10) else null
            val holdLine: String? = st?.nativeStopLevel?.takeIf { it in 1..100 }?.let { getString(R.string.charge_meter_holds_line, it) }
            val bigText = listOfNotNull(chargerLine, battLine, holdLine).joinToString("\n").ifBlank { null }
            val collapsed = chargerLine ?: battLine

            if (stopped || !prefs.chargeMeterEnabled) return@launch   // toggled off mid-read: no ghost
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(title, collapsed, bigText, iconValue, iconUnit, prefs.chargeMeterStyle))
            } catch (e: Exception) { LogExt().e(javaClass.simpleName, "notify failed: ${e.message}") }
        }
    }


    // ---- notification + dynamic number icon ----

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                // DEFAULT importance = the icon reaches the status-bar strip; everything else off so
                // it stays completely silent (no sound/vibration/light/badge, and no heads-up since
                // the notification uses setOnlyAlertOnce).
                val ch = NotificationChannel(CHANNEL_ID, getString(R.string.charge_meter_channel), NotificationManager.IMPORTANCE_DEFAULT)
                ch.setShowBadge(false)
                ch.enableVibration(false)
                ch.enableLights(false)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(
        title: String, collapsed: String?, bigText: String?,
        iconValue: String?, iconUnit: String?, style: String
    ): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        // A notification with no title/text at all is suppressed WHOLESALE by some ROMs (MIUI drops
        // the strip icon along with the empty shade row - device-verified on the Mi A3), and stock
        // Android never lets an FGS row be removed anyway. So there is no "number only, no
        // notification" mode on Android: every style keeps a one-line shade row + a Stop action.
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)   // updates every 3s must NOT re-alert
            // Silence via the channel (O+) and explicit null sound/vibration (pre-O), NOT setSilent():
            // setSilent forces the notification into an implicit "silent" group with no summary, an
            // unwanted coupling for a solo ongoing notification. Channel importance keeps the strip icon.
            .setSound(null)
            .setVibrate(null)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Android 12+ defers a foreground-service notification ~10s unless marked immediate; a live
            // meter must appear the moment it starts, so opt out of the deferral.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // A foreground-service notification can never be swiped away (the OS forces
            // FLAG_FOREGROUND_SERVICE while startForeground() is active). Give a one-tap way to kill it.
            .addAction(0, getString(R.string.charge_meter_stop_action), PendingIntent.getService(
                this, 0, Intent(this, ChargeMeterService::class.java).setAction(ACTION_USER_STOP),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT
            ))

        // Strip icon: the live number ("both") - ALWAYS rendered, never the battery drawable
        // (that stale battery glyph on a swipe re-post was the bug). "notif" style keeps a plain
        // static icon since the details, not the strip number, are the point there.
        val wantNumber = style != "notif"
        val icon = when {
            wantNumber && iconValue != null && iconUnit != null -> numberIcon(iconValue, iconUnit)
            wantNumber -> numberIcon("·", "")   // placeholder dot, never a battery glyph
            else -> null
        }
        if (icon != null) b.setSmallIcon(icon) else b.setSmallIcon(R.drawable.ic_battery_charging_full)

        // Shade content: the detailed dashboard, for every style.
        if (!bigText.isNullOrBlank()) {
            b.setContentText(collapsed ?: bigText.substringBefore('\n'))
            b.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        return b.build()
    }

    /**
     * Status-bar icon. The strip slot is fixed at ~24dp by the OS (a larger bitmap is scaled down,
     * not shown bigger), so only 2-3 bold glyphs are ever legible - which is why real meter apps
     * (3C, BatteryBot) put ONE number in the strip and push the unit/sign into the shade. Default:
     * a single number filling the WHOLE square edge-to-edge (measured, not a fixed ratio) so it is
     * as large as the slot physically allows. If the user opts into a unit line, it drops to a
     * two-tier layout (number on top, unit below). White on transparent so the system tints it.
     */
    private fun numberIcon(value: String, unit: String): IconCompat? {
        return try {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // Weight and size are fixed, not user settings: bold (a stroke pass past plain BOLD so
            // it survives the downscale to the strip) at the largest fill the slot allows.
            val stroke = size * 0.022f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL_AND_STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND
            }
            val fill = 0.96f
            if (unit.isBlank()) {
                drawFilled(canvas, p, value, size.toFloat(), fill, 0f, size.toFloat())
            } else {
                drawFilled(canvas, p, value, size.toFloat(), fill, 0f, size * 0.63f)
                drawFilled(canvas, p, unit, size.toFloat(), fill * 0.86f, size * 0.60f, size.toFloat())
            }
            IconCompat.createWithBitmap(bmp)
        } catch (e: Exception) {
            LogExt().e(javaClass.simpleName, "numberIcon failed: ${e.message}"); null
        }
    }

    /**
     * Draw [text] centered in the full width and the vertical band [top,bottom], scaled up until it
     * fills [fill] of that box in whichever dimension binds first (measured via getTextBounds, so a
     * 2-char value renders far larger than a 4-char one - always as big as the glyphs allow).
     */
    private fun drawFilled(canvas: Canvas, p: Paint, text: String, size: Float, fill: Float, top: Float, bottom: Float) {
        if (text.isEmpty()) return
        val bandH = bottom - top
        p.textSize = 100f
        val b = android.graphics.Rect()
        p.getTextBounds(text, 0, text.length, b)
        val w = (b.width().takeIf { it > 0 } ?: 1)
        val h = (b.height().takeIf { it > 0 } ?: 1)
        p.textSize = 100f * minOf(size * fill / w, bandH * fill / h)
        val b2 = android.graphics.Rect()
        p.getTextBounds(text, 0, text.length, b2)
        // baseline so the glyph's optical center lands on the band center
        val cy = (top + bottom) / 2f - (b2.top + b2.bottom) / 2f
        canvas.drawText(text, size / 2f, cy, p)
    }
}
