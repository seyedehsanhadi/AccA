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
