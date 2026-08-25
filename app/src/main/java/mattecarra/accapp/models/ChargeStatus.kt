package mattecarra.accapp.models

fun chargeStatusWord(plugged: Boolean, measuredClass: String?): String = when {
    !plugged -> "Discharging"
    measuredClass.equals("bypass", true) -> "Bypass"
    measuredClass.equals("idle", true) || measuredClass.equals("standby", true) -> "Idle"
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) -> "Draining"
    measuredClass.equals("charging", true) -> "Charging"
    // A cut means the input is held OFF: the cable is in, the battery is not charging. It used to
    // fall through to the "Charging" default, so every phone whose switch is an input cut reported
    // Charging while ACC was holding it. Idle is the honest word -- plugged, battery flat.
    measuredClass.equals("cut", true) || measuredClass.equals("cut-input", true) -> "Idle"
    else -> "Charging"
}


/**
 * Is the battery taking charge right now? One definition for every surface.
 *
 * The dashboard, the widget label and the widget's self-refresh cadence each had their own copy.
 * The widget's asked the kernel first, so a pause-hold printed "Charging speed" over a negative
 * current; the dashboard's listed "cut" but not "cut-input", so an input-cut phone fell through to
 * the kernel status and said the same thing.
 *
 * `signedMa` is the last resort for a daemon that reports neither a class nor a usable status.
 */
fun isChargingNow(measuredClass: String?, status: String?, signedMa: Float? = null): Boolean = when {
    measuredClass.equals("charging", true) -> true
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) ||
    measuredClass.equals("bypass", true) || measuredClass.equals("idle", true) ||
    measuredClass.equals("standby", true) || measuredClass.equals("cut", true) ||
    measuredClass.equals("cut-input", true) -> false
    status.equals("Charging", true) -> true
    status?.contains("Discharging", true) == true -> false
    else -> (signedMa ?: 0f) > 80f
}
