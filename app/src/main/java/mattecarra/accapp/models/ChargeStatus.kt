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
    // A DECISIVE MEASUREMENT OUTRANKS EVERY INFERENCE, IN BOTH DIRECTIONS.
    //
    // The negative half of this rule was already here and the positive half was missing, so the
    // contradiction it exists to prevent came back mirrored: ACC reporting class "charging" while
    // the kernel said "Not charging" fell past every arm to a bare `signedMa != null -> Idle`,
    // and the dashboard printed "Idle" directly above "+1000 mA". A class of "idle", or a cut that
    // is no longer holding, did the same. Current into the pack above the band IS charging,
    // whatever the class and the kernel say about it.
    signedMa != null && signedMa < -IDLE_BAND_MA -> "Draining"
    signedMa != null && signedMa > IDLE_BAND_MA -> "Charging"
    measuredClass.equals("bypass", true) -> "Bypass"
    measuredClass.equals("idle", true) || measuredClass.equals("standby", true) -> "Idle"
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) -> "Draining"
    // A cut IS a statement: ACC is holding the input off. With no decisive current to contradict
    // it, that earns Idle - unlike the token "unknown", which asserts nothing. Reached only after
    // the measurement arms above, so a cut that is no longer holding still reads as charging.
    measuredClass.equals("cut", true) || measuredClass.equals("cut-input", true) -> "Idle"
    // A cut means the input is held OFF: the cable is in, the battery is not charging. It used to
    // fall through to the "Charging" default, so every phone whose switch is an input cut reported
    // Charging while ACC was holding it. It then returned "Idle" unconditionally, which put the
    // same contradiction back for a cut phone running off its own pack. Both directions are
    // settled by the two measurement arms above; what is left here is the inference-only case.
    isChargingNow(measuredClass, status, signedMa) -> "Charging"
    // Inside the band, in either direction: the pack really is sitting still. This is the ONLY
    // reading that earns the word.
    signedMa != null -> "Idle"
    // No reading at all. Only an EXPLICIT statement from the kernel justifies a word here -- Full,
    // Not charging, Discharging. The literal token "unknown", which ACC exports for a class it
    // could not determine and some kernels put in `status`, is not a statement: counting it as one
    // is how "Idle" came to be printed for a phone that had told us nothing.
    isNotChargingStatus(status) -> "Idle"
    else -> "Unknown"
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
    // ...and the mirror of it. A measured current INTO the pack above the band is charging, even
    // when the class or the kernel says otherwise; without this the widget and the meter could
    // still answer "not charging" over a positive reading.
    signedMa != null && signedMa > IDLE_BAND_MA -> true
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

internal fun isNotChargingStatus(status: String?): Boolean =
    status?.let {
        it.contains("Discharging", true) || it.contains("Not charging", true) ||
        it.equals("Full", true)
    } ?: false
