package xml

import android.app.PendingIntent
import mattecarra.accapp.widget.pendingFlags
import android.appwidget.*
import android.appwidget.AppWidgetManager.*
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.Context.MODE_PRIVATE
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.preference.PreferenceManager
import android.util.TypedValue.COMPLEX_UNIT_SP
import android.view.View.*
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat.startActivity
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mattecarra.accapp.Preferences
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.activities.BatteryDialogActivity
import mattecarra.accapp.database.AccaRoomDatabase
import mattecarra.accapp.models.AccState
import mattecarra.accapp.models.DashboardValues
import mattecarra.accapp.models.chargeStatusWord
import mattecarra.accapp.models.StateFormat
import mattecarra.accapp.models.isChargingNow
import mattecarra.accapp.services.ChargeMeterService
import mattecarra.accapp.services.WidgetService
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ProfileUtils
import java.util.*

const val WIDGET_ACTION_CLICK = "acca.action.WIDGET_CLICK_BATTERY"
const val WIDGET_ACTION_REVERSE = "acca.action.WIDGET_CLICK_REVERSE"
const val WIDGET_ALL_ENABLED = "acca.event.WIDGET_ALL_ENABLE"
const val WIDGET_ONE_UPDATE = "acca.action.WIDGET_ONE_UPDATE"
const val WIDGET_ALL_UPDATE = "acca.action.WIDGET_ALL_UPDATE"
const val WIDGET_ALL_DISABLED = "acca.event.WIDGET_ALL_DISABLE"

const val WIDGET_ID_NAME = "widget_id"
const val WIDGET_PREF_NAME = "widget_pref"
const val WIDGET_ALL_STOP = "widget_manStop"

const val WIDGET_TCOLOR = "_tcolor"
const val WIDGET_TSIZE = "_tsize"
const val WIDGET_BCOLOR = "_bcolor"
const val WIDGET_BROUND = "_bround"

const val WIDGET_SLABELS = "_slabels"
const val WIDGET_SSYMBOL = "_ssymbol"
const val WIDGET_SVALUE = "_svalue"

const val WIDGET_SSTATUS = "_sstatus"
const val WIDGET_SCURRENT = "_scurrent"
const val WIDGET_STEMP = "_stemp"
const val WIDGET_SVOLT = "_svolt"
const val WIDGET_SPROFILE = "_sprofile"

class BatteryInfoWidget : AppWidgetProvider()
{
    @Override
    override fun onReceive(context: Context?, intent: Intent?)
    {
        super.onReceive(context, intent)
        if (context == null) return

        //--------------------------------------------------
        // select locale and theme day\night

        val spl = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getString("language", "def")

        val config = context.resources.configuration
        val locale = if (spl.equals("def")) Locale.getDefault() else Locale(spl)

        Locale.setDefault(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) config.setLocale(locale) else config.locale = locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createConfigurationContext(config)

        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        //--------------------------------------------------

        //AppWidgetAlarm(context).startLongUpdateAlarm()  // start long alarm with pendingIntent, RTC

        LogExt().d(javaClass.simpleName, ".onReceive(): " + intent?.action)

        when(intent?.action)
        {
            ACTION_APPWIDGET_DELETED ->
            {
                // delete setting for current widget
            }

            ACTION_APPWIDGET_ENABLED ->
            {
                // register widget receivers - send only no protected intent !!!
                WidgetService().runSelfIntent(context, Intent().setAction(WIDGET_ALL_ENABLED))
            }

            ACTION_APPWIDGET_DISABLED ->
            {
                //AppWidgetAlarm(context).stopLongUpdateAlarm()  // stop long alarm
                WidgetService().runSelfIntent(context, Intent().setAction(WIDGET_ALL_DISABLED))
            }

            WIDGET_ALL_UPDATE,
            ACTION_APPWIDGET_UPDATE ->
            {
                goAsync().let { pr -> updateAllWidget(context, getAppWidgetIds(context)) { pr.finish() } }
            }

            WIDGET_ONE_UPDATE,
            ACTION_APPWIDGET_OPTIONS_CHANGED ->
            {
                var widgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, -1)
                if (widgetId == -1) widgetId = intent.getIntExtra(WIDGET_ID_NAME, -1)
                if (widgetId > -1) goAsync().let { pr -> updateOneWidget(context, widgetId) { pr.finish() } }
            }

            WIDGET_ACTION_REVERSE ->
            {
                // Use Reverse manualStop
                val SP = context.getSharedPreferences(WIDGET_PREF_NAME, MODE_PRIVATE)
                val manualStop = SP.getBoolean(WIDGET_ALL_STOP, false).not()
                Toast.makeText(context, "${ if(manualStop) "Sleeping" else "Fast"} mode", Toast.LENGTH_SHORT).show()
                SP.edit().putBoolean(WIDGET_ALL_STOP, manualStop).apply()
                goAsync().let { pr -> updateAllWidget(context, getAppWidgetIds(context)) { pr.finish() } }
            }

            WIDGET_ACTION_CLICK ->
            {
                startActivity(context.applicationContext, Intent(context.applicationContext, BatteryDialogActivity::class.java)
                    .addFlags(FLAG_ACTIVITY_NEW_TASK).putExtras(intent), null)
            }
        }
    }

    //------------------------------------------------------------------

    @Override
    override fun onUpdate(context: Context?, appWidgetManager: AppWidgetManager?, appWidgetIds: IntArray?)
    {
        super.onUpdate(context, appWidgetManager, appWidgetIds) // to call onReceive
    }

    //------------------------------------------------------------------

    fun updateAllWidget(context: Context, widgetIds: IntArray?, onDone: (() -> Unit)? = null)
    {
        // N widgets, ONE goAsync token: release it when the last one finishes, not the first.
        if (widgetIds == null || widgetIds.isEmpty()) { onDone?.invoke(); return }
        val remaining = java.util.concurrent.atomic.AtomicInteger(widgetIds.size)
        for (one in widgetIds) updateOneWidget(context, one) {
            if (remaining.decrementAndGet() == 0) onDone?.invoke()
        }
    }

    /**
     * @param onDone released once the async work finishes; onReceive passes the goAsync() token.
     *
     * onReceive() used to call this and return immediately, leaving root calls, a Room query and
     * the RemoteViews update running in an unstructured GlobalScope job with nothing telling
     * Android the receiver was still busy. The process could be killed mid-update - worst when
     * the charge-meter foreground service is disabled, which is the default, because then
     * nothing else is holding the process up either.
     */
    fun updateOneWidget(context: Context, widgetId: Int, onDone: (() -> Unit)? = null)
    {
        val sp = context.getSharedPreferences(WIDGET_PREF_NAME, MODE_PRIVATE)
        val manualStop = sp.getBoolean(WIDGET_ALL_STOP, false)
        val widgetView = RemoteViews(context.packageName, R.layout.widget_battery_info)

        if (manualStop) { // sleep mode

            widgetView.setViewVisibility(R.id.battery_info_ll, INVISIBLE) // foreground
            widgetView.setViewVisibility(R.id.battery_icon, VISIBLE)

            widgetView.setOnClickPendingIntent(R.id.dash_click_left_zone, // click
                PendingIntent.getBroadcast(context, widgetId,
                Intent(context, BatteryInfoWidget::class.java).setAction(WIDGET_ACTION_CLICK).putExtra(WIDGET_ID_NAME, widgetId),
                pendingFlags(PendingIntent.FLAG_CANCEL_CURRENT)))

            widgetView.setOnClickPendingIntent(R.id.dash_click_right_zone, // click
                PendingIntent.getBroadcast(context, widgetId,
                    Intent(context, BatteryInfoWidget::class.java).setAction(WIDGET_ACTION_REVERSE),
                    pendingFlags(PendingIntent.FLAG_CANCEL_CURRENT)))

            getInstance(context).updateAppWidget(widgetId, widgetView) // update
            onDone?.invoke()   // sleep-mode path returns early; release the receiver
            return
        }

        //----------------------------------------------------------------------------------

        GlobalScope.launch {

            try {

            val dashboardValues =
                DashboardValues(Acc.instance.getBatteryInfo(), Acc.instance.isAccdRunning())
            // `acc -i` alone cannot say whether the raw current sign is trustworthy, nor whether
            // ACC is holding the input open. The daemon's --state snapshot carries polarity,
            // measuredClass and the charger side, and the dashboard already reads it -- the widget
            // was the last surface still showing the unfiltered kernel answer.
            // The meter tick that triggered this render has usually just read --state. Reuse it
            // rather than spawning a second root shell for the same answer a moment later; falls
            // back to reading when it is stale or the meter is not running.
            val accState = ChargeMeterService.recentState() ?: Acc.instance.getState()
            with(dashboardValues) {

                val swidgetId = widgetId.toString()
                val showLabel = sp.getBoolean(swidgetId + WIDGET_SLABELS, true)
                val replaceLabel = sp.getBoolean(swidgetId + WIDGET_SSYMBOL, false)
                val showEndvalue = sp.getBoolean(swidgetId + WIDGET_SVALUE, true)

                widgetView.setViewVisibility(R.id.battery_info_ll, VISIBLE)
                widgetView.setViewVisibility(R.id.battery_icon, INVISIBLE)

                widgetView.setViewVisibility(R.id.status_line, if (sp.getBoolean(swidgetId + WIDGET_SSTATUS, true)) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.charging_line, if (sp.getBoolean(swidgetId + WIDGET_SCURRENT, true)) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.temper_line, if (sp.getBoolean(swidgetId + WIDGET_STEMP, true)) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.voltage_line, if (sp.getBoolean(swidgetId + WIDGET_SVOLT, true)) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.profile_line, if (sp.getBoolean(swidgetId + WIDGET_SPROFILE, true)) VISIBLE else GONE)

                widgetView.setViewVisibility(R.id.status_label, if (showLabel) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.charging_label, if (showLabel) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.temper_label, if (showLabel) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.voltage_label, if (showLabel) VISIBLE else GONE)
                widgetView.setViewVisibility(R.id.profile_label, if (showLabel) VISIBLE else GONE)

                val textColor = sp.getInt(swidgetId + WIDGET_TCOLOR, Color.BLACK)
                val textSize = sp.getInt(swidgetId + WIDGET_TSIZE, 14)
                val roundSize = sp.getInt(swidgetId + WIDGET_BROUND, 8)
                val backgroundColor = sp.getInt(swidgetId + WIDGET_BCOLOR, Color.parseColor("#FFF5F5F5"))
                val options: Bundle = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                val mWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 100)
                val mHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 100)
                val gradient = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(backgroundColor, backgroundColor))

                gradient.shape = GradientDrawable.RECTANGLE
                gradient.cornerRadius = roundSize.toFloat()
                widgetView.setImageViewBitmap(R.id.imbkgfck, gradient.toBitmap(mWidth, mHeight))

                widgetView.setTextViewText(R.id.status_label, if (replaceLabel) "Ⓢ:" else context.getString(R.string.info_status))
                widgetView.setTextViewText(R.id.status_out,
                    accState?.let { chargeStatusWord(it.plugged, it.measuredClass, it.status,
                        it.signedCurrentMilliAmps()) } ?: batteryInfo.status)
                widgetView.setTextViewTextSize(R.id.status_label, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextViewTextSize(R.id.status_out, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextColor(R.id.status_label, textColor)
                widgetView.setTextColor(R.id.status_out, textColor)

                // The number is normalised below, but the WORD was still the kernel's answer:
                // a pause-hold prints status Charging, so the widget said "Charging speed" over a
                // negative current. Same precedence the dashboard uses -- measuredClass first.
                // Give the rule its third input. measuredClass is refreshed on a slower cadence than the
                // current, so a stale "charging" class over a freshly negative reading printed "Charging
                // speed" above a discharging number. The signed current is the tie-break.
                val chargingNow = accState?.let { isChargingNow(it.measuredClass, it.status, it.signedCurrentMilliAmps()) }
                    ?: batteryInfo.isCharging()

                widgetView.setTextViewText(R.id.charging_label, if (replaceLabel) "Ⓒ:"
                else if (chargingNow) context.getString(R.string.info_charging_speed)
                     else context.getString(R.string.info_discharging_speed))

                val prefc = Preferences(context)

                // normaliseMilliAmps() takes the RAW reading out of --state. `acc -i` has ALREADY
                // applied polarity, so feeding its value through it corrects an already-corrected
                // number: measured on a Mi A3 charging, --state current_raw was -1344605 while
                // `acc -i` reported current_now 1.23A, and normalising that positive value flipped
                // the widget to -1230 mA on a phone that was charging. Ask the state for its own
                // signed figure, exactly as the dashboard does, and only fall back to batteryInfo
                // when there is no state at all.
                val rawMa = batteryInfo.getCurrentNow(prefc.currentInputUnitOfMeasure)
                val normMa = accState?.signedCurrentMilliAmps()
                // Only reached when there is no --state to ask; `acc -i` already carries the sign
                // on rc9+ daemons, so it is passed through untouched there.
                val plus = if (Acc.instance.version < 202107280) batteryInfo.isCharging() else true
                // Same source as the sign and the watts: one measurement, not three.
                val amps = if (normMa != null)
                    StateFormat.current(normMa, prefc.currentOutputUnitOfMeasure)
                else batteryInfo.getCurrentNow(prefc.currentInputUnitOfMeasure,
                    prefc.currentOutputUnitOfMeasure, plus, showEndvalue)
                // The row is labelled "To battery", so the watts beside it must be the battery
                // side. chargeWatts is the CHARGER side and read high next to a battery mA:
                // 2820 mA at 4.04 V is 11.4 W into the pack, printed as the supply's 13 W.
                val watts = accState?.let { st ->
                    val w = AccState.batteryWatts(normMa ?: rawMa, st.voltageRaw)
                    if (kotlin.math.abs(w) >= 0.1f) String.format("  %.1f W", w) else null
                } ?: ""
                widgetView.setTextViewText(R.id.charging_out, amps + watts)

                widgetView.setTextViewTextSize(R.id.charging_label, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextViewTextSize(R.id.charging_out, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextColor(R.id.charging_label, textColor)
                widgetView.setTextColor(R.id.charging_out, textColor)

                widgetView.setTextViewText(R.id.temper_label, if (replaceLabel) "Ⓣ:" else context.getString(R.string.info_temperature))
                // Prefer --state, as the dashboard does. Reading temperature and voltage from the
                // `acc -i` scrape while the amps and the status came from --state is what left the
                // dashboard split-brained, and the widget was still doing it: it printed 4.040 V
                // beside the dashboard's 4.036 V, because `acc -i` rounds to two decimals first.
                widgetView.setTextViewText(R.id.temper_out,
                    accState?.let { StateFormat.temperature(it.tempDeciC, prefc.temperatureOutputUnitOfMeasure) }
                        ?: batteryInfo.getTemperature(prefc.temperatureOutputUnitOfMeasure, showEndvalue))
                widgetView.setTextViewTextSize(R.id.temper_label, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextViewTextSize(R.id.temper_out, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextColor(R.id.temper_label, textColor)
                widgetView.setTextColor(R.id.temper_out, textColor)

                widgetView.setTextViewText(R.id.voltage_label, if (replaceLabel) "Ⓥ:" else context.getString(R.string.info_voltage))
                widgetView.setTextViewText(R.id.voltage_out,
                    accState?.let { StateFormat.voltage(it.voltageRaw, prefc.voltageOutputUnitOfMeasure) }
                        ?: batteryInfo.getVoltageNow(prefc.voltageInputUnitOfMeasure, prefc.voltageOutputUnitOfMeasure, showEndvalue))
                widgetView.setTextViewTextSize(R.id.voltage_label, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextViewTextSize(R.id.voltage_out, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextColor(R.id.voltage_label, textColor)
                widgetView.setTextColor(R.id.voltage_out, textColor)

                widgetView.setTextViewText(R.id.profile_label, if (replaceLabel) "Ⓟ:" else context.getString(R.string.info_profile))
                widgetView.setTextViewText(R.id.profile_out, getCurrentProfile(context))
                widgetView.setTextViewTextSize(R.id.profile_label, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextViewTextSize(R.id.profile_out, COMPLEX_UNIT_SP, textSize.toFloat())
                widgetView.setTextColor(R.id.profile_label, textColor)
                widgetView.setTextColor(R.id.profile_out, textColor)

                widgetView.setOnClickPendingIntent(R.id.dash_click_left_zone,
                    PendingIntent.getBroadcast(context, widgetId,
                    Intent(context, BatteryInfoWidget::class.java).setAction(WIDGET_ACTION_CLICK)
                    .putExtra(WIDGET_ID_NAME, widgetId), pendingFlags(PendingIntent.FLAG_CANCEL_CURRENT)))

                widgetView.setOnClickPendingIntent(R.id.dash_click_right_zone, // click
                    PendingIntent.getBroadcast(context, widgetId,
                        Intent(context, BatteryInfoWidget::class.java).setAction(WIDGET_ACTION_REVERSE),
                        pendingFlags(PendingIntent.FLAG_CANCEL_CURRENT)))

                getInstance(context).updateAppWidget(widgetId, widgetView)

                // Always ask for the next tick. The service decides whether there is one: it drops
                // the request when the screen is off, and picks the cadence from isCharging. Asking
                // only while charging is what froze the figure on a discharging phone.
                LogExt().d(javaClass.simpleName, "SelfUpdate $swidgetId, chargingNow=$chargingNow")
                if (!ChargeMeterService.isDrivingWidgets()) {
                    WidgetService().runSelfIntent(context, Intent().setAction(WIDGET_ONE_UPDATE)
                    .putExtra(WIDGET_ID_NAME, widgetId).putExtra("isCharging", chargingNow))
                }
            }

            } catch (e: Exception) {
                // Acc.instance getter / getBatteryInfo / version / Room getCurrentProfile can
                // throw on a routine widget update -> never let it crash the process.
                // Always-on: this failure is invisible on the home screen -- the widget just keeps
                // showing its placeholder icon -- so a debug-level line meant no trace at all.
                LogExt().s(javaClass.simpleName, "updateOneWidget failed: " + e.toString())
            }
            finally { onDone?.invoke() }
        }
    }

    suspend fun getCurrentProfile(context: Context): String
    {
        val profileId = ProfileUtils.getCurrentProfile(PreferenceManager.getDefaultSharedPreferences(context))
        val curProf = AccaRoomDatabase.getDatabase(context.applicationContext).profileDao().getProfileById(profileId)
        return if (curProf != null) curProf.profileName else context.getString(R.string.profile_not_selected)
    }

    fun getAppWidgetIds(context: Context): IntArray
    {
        val thisAppWidgetComponentName = ComponentName(context.packageName, javaClass.name)
        return AppWidgetManager.getInstance(context).getAppWidgetIds(thisAppWidgetComponentName)
    }

}