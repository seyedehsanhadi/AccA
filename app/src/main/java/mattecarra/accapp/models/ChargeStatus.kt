package mattecarra.accapp.models

fun chargeStatusWord(plugged: Boolean, measuredClass: String?, status: String? = null): String = when {
    !plugged -> "Discharging"
    measuredClass.equals("bypass", true) -> "Bypass"
    measuredClass.equals("idle", true) || measuredClass.equals("standby", true) -> "Idle"
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) -> "Draining"
    // A cut means the input is held OFF: the cable is in, the battery is not charging. It used to
    // fall through to the "Charging" default, so every phone whose switch is an input cut reported
    // Charging while ACC was holding it. Idle is the honest word -- plugged, battery flat.
    measuredClass.equals("cut", true) || measuredClass.equals("cut-input", true) -> "Idle"
    // The word had its own copy of the rule and never looked at the kernel, so the same Pixel 6a
    // that drained at -362 mA behind a native level limit printed "Charging" on the dashboard
    // while the current beside it was negative. Route the tail through the one rule.
    isChargingNow(measuredClass, status) -> "Charging"
    else -> "Idle"
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
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) ||
    measuredClass.equals("bypass", true) || measuredClass.equals("idle", true) ||
    measuredClass.equals("standby", true) || measuredClass.equals("cut", true) ||
    measuredClass.equals("cut-input", true) -> false
    // measuredClass is an inference; an explicit negative from the kernel is a fact. Measured on a
    // Pixel 6a held at its native level limit: ACC reported measuredClass "charging" for minutes
    // while the kernel said "Not charging" and the pack drained at -268 mA. Where the two disagree,
    // take the non-charging answer -- in BOTH directions, since the reverse case (kernel "Charging"
    // during an ACC input cut) is the one that made the widget claim charging over a cut.
    measuredClass.equals("charging", true) -> !isNotChargingStatus(status)
    status.equals("Charging", true) -> true
    isNotChargingStatus(status) -> false
    else -> (signedMa ?: 0f) > 80f
}

private fun isNotChargingStatus(status: String?): Boolean =
    status?.let {
        it.contains("Discharging", true) || it.contains("Not charging", true) ||
        it.equals("Full", true)
    } ?: false
