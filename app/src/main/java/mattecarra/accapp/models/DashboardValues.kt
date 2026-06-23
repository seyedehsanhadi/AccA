package mattecarra.accapp.models

data class DashboardValues(
    var batteryInfo: BatteryInfo,
    var daemon: Boolean?,
    // rc9+ structured snapshot, preferred over batteryInfo when present; null on older
    // daemons (the fragment then falls back to the legacy `acca -i`-derived batteryInfo).
    var state: AccState? = null
) {
}