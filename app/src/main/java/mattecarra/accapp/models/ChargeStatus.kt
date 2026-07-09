package mattecarra.accapp.models

fun chargeStatusWord(plugged: Boolean, measuredClass: String?): String = when {
    !plugged -> "Discharging"
    measuredClass.equals("bypass", true) -> "Bypass"
    measuredClass.equals("idle", true) || measuredClass.equals("standby", true) -> "Idle"
    measuredClass.equals("drain", true) || measuredClass.equals("discharging", true) -> "Draining"
    measuredClass.equals("charging", true) -> "Charging"
    else -> "Charging"
}
