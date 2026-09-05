package mattecarra.accapp.models

/**
 * `signedMa` separates the two ways a plugged phone can fail to charge. Idle means the battery is
 * sitting still -- roughly no current either way. Draining means the phone is running off the pack
 * while the cable is in, which is what a level hold looks like on a Pixel 6a: -311 mA behind a
 * firmware stop. Calling that "Idle" contradicted the current printed on the next line.
 */
fun chargeStatusWord(plugged: Boolean, measuredClass: String?, status: String? = null,
                     signedMa: Float? = null): String = when {
    !plugged -> "Discharging"
    measuredClass.equals("bypass", true) -> "Bypass"
    measuredClass.equals("idle", true) || measuredClass.equals("standby", true) -> "Idle"
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) -> "Draining"
    // A cut means the input is held OFF: the cable is in, the battery is not charging. It used to
    // fall through to the "Charging" default, so every phone whose switch is an input cut reported
    // Charging while ACC was holding it. It then returned "Idle" unconditionally, which put the
    // same contradiction back for a cut phone running off its own pack: Idle printed above -311 mA.
    // A cut says nothing about which of the two is happening, so let the current decide, below.
    // The word had its own copy of the rule and never looked at the kernel, so the same Pixel 6a
    // that drained at -362 mA behind a native level limit printed "Charging" on the dashboard
    // while the current beside it was negative. Route the tail through the one rule.
    isChargingNow(measuredClass, status, signedMa) -> "Charging"
    signedMa != null && signedMa < -IDLE_BAND_MA -> "Draining"
    else -> "Idle"
}

/** Below this, in either direction, the pack is doing nothing worth naming. */
const val IDLE_BAND_MA = 50f


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
    // A sustained negative current is a measurement; the class and the kernel status are both
    // inferences and both have been observed wrong together. A laurus charger that collapsed to
    // 5 mA, and a native level latch, each left "charging"/"Charging" standing while the pack
    // drained. Where a real reading contradicts them, the reading wins.
    signedMa != null && signedMa < -IDLE_BAND_MA -> false
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
