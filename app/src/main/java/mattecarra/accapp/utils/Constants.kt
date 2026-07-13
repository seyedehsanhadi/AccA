package mattecarra.accapp.utils

object Constants {
    val PROFILE_KEY = "profile"
    val PROFILE_CONFIG_KEY = "profileConfig"
    val PROFILE_ID_KEY = "profileId"
    val ACC_HAS_CHANGES = "hasChanges"
    val ACC_CONFIG_KEY = "accConfig"
    val TITLE_KEY = "title"
    val DATA_KEY = "data"

    // CONFIG KEYS
    val SCHEDULE_ID_KEY = "schedId"
    val SCHEDULE_NAME_KEY = "schedName"
    val SCHEDULE_TIME_KEY = "schedTime"
    val SCHEDULE_EXEC_ONCE_KEY = "schedExecOnce"
    val SCHEDULE_EXEC_ONBOOT_KEY = "schedExecOnBoot"
    val SCHEDULE_ENABLED_KEY = "schedEnabled"

    // PREFERENCE KEYS
    val CURRENT_INPUT_UNIT_OF_MEASURE = "current_measure_unit"
    val VOLTAGE_INPUT_UNIT_OF_MEASURE = "voltage_measure_unit"
    val CURRENT_OUTPUT_UNIT_OF_MEASURE = "current_measure_output"
    val VOLTAGE_OUTPUT_UNIT_OF_MEASURE = "voltage_measure_output"
    val TEMPERATURE_OUTPUT_UNIT_OF_MEASURE = "temperature_measure_output"
    val THEME = "theme"
    val ACC_VERSION = "acc_version"
    val DJS_ENABLED = "djs_enabled"
    val INCLUDE_PRE_RELEASES = "include_pre_releases"
    // Status-bar charge meter (rc15)
    val CHARGE_METER_ENABLED = "charge_meter_enabled"
    val CHARGE_METER_DISPLAY = "charge_meter_display"   // auto | w | ma
    val CHARGE_METER_STYLE = "charge_meter_style"       // icon | notif | both
    val CHARGE_METER_BATTERY_SOURCE = "charge_meter_battery_source"   // acc | system
    val CHARGE_METER_SHOW_TEMP = "charge_meter_show_temp"   // off by default
    val ACCD_USER_STOPPED = "accd_user_stopped"   // deliberate daemon stop; plug guard respects it
    val AUTO_REKICK_ON_PLUG = "auto_rekick_on_plug"   // opt-in: re-kick fast charge on plug (guarded)

    // ACC is no longer auto-installed by AccA; users flash the module themselves.
    val ACC_RELEASE_URL = "https://github.com/seyedehsanhadi/acc/releases/latest"
    val ACCA_RELEASE_URL = "https://github.com/seyedehsanhadi/AccA/releases/latest"
}
