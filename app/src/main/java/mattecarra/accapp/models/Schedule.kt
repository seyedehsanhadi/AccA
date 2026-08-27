package mattecarra.accapp.models

import mattecarra.accapp.acc.Acc
import mattecarra.accapp.acc.ConfigUpdater
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.djs.Djs
import mattecarra.accapp.djs.DjsSchedule
import java.lang.StringBuilder

// `cue` carries the gates that were in force when the schedule was written: the global
// AccVoltControl / AccCurrentMax opt-outs, and the source profile's own section switches. The
// command string is baked into DJS at add/edit time, so these are the gates that matter. Reading a
// schedule back from DJS used to default to all-enabled, and the claim that "that path never
// rebuilds the command" was wrong: the toggle in SchedulesFragment calls editSchedule, which
// rebuilds it in full. ScheduleProfile.cueMask now persists these gates and refreshSchedules
// restores them, so a read-modify-write cycle no longer re-enables what the user switched off.
data class Schedule(val isEnabled: Boolean, val time: String, val executeOnce: Boolean, val executeOnBoot: Boolean, val profile: ScheduleProfile, val cue: ConfigUpdaterEnable = ConfigUpdaterEnable()) {
    private val timeRegex = """([0-9]{2})([0-9]{2})""".toRegex()

    companion object {
        // DJS runs scheduled/boot commands very early -- a BOOT schedule fires BEFORE ACC's own service
        // creates the runtime symlink /dev/.vr25/acc/acca. The command builders hardcode that runtime
        // path, so at boot every sub-command exits 127 and the profile silently never applies (the
        // 6.3->6.4 "morning day-profile didn't switch" regression). The persistent script
        // /data/adb/vr25/acc/acca.sh (the symlink's own target) exists as soon as /data is mounted and
        // writes config standalone, so rewrite the path for SCHEDULED commands only. Live probes that use
        // the /dev path to mean "daemon is up" are not built here and stay untouched.
        private const val RUNTIME_ACCA = "/dev/.vr25/acc/acca"
        private const val BOOT_SAFE_ACCA = "/data/adb/vr25/acc/acca.sh"
    }

    fun getCommand(): String {
        // The profile body uses the boot-safe acca.sh path (the A5 fix above).
        val body = (": accaScheduleId${profile.uid}; ${ConfigUpdater(profile.accConfig, cue).concatenateCommands(Acc.instance)}")
            .replace(RUNTIME_ACCA, BOOT_SAFE_ACCA)

        val string = StringBuilder()
        // A BOOT schedule races ACC's own boot config init: if the profile applies BEFORE accd has loaded,
        // accd overwrites it and the morning switch silently doesn't happen (the intermittent "didn't switch
        // to the day profile"). Wait for ACC's runtime symlink (accd up) + a short settle past accd's initial
        // config write, THEN apply -- accd then re-reads the new config on its mtime poll and keeps it. The
        // until probes the /dev runtime path (the "daemon is up" signal) and is NOT rewritten to acca.sh.
        if (executeOnBoot || isBootSchedule())
            string.append("until [ -x ").append(RUNTIME_ACCA).append(" ]; do sleep 1; done; sleep 8; ")

        string.append(body)

        if(executeOnce)
            string.append("; : --delete")

        if(executeOnBoot)
            string.append("; : --boot")

        return string.toString()
    }

    fun getTime(): Time? {
        return if(isBootSchedule())
            null
        else
            timeRegex.find(time)?.destructured?.let { (hour: String, minute: String) ->
                Time(hour.toInt(), minute.toInt())
            }
    }

    fun isBootSchedule(): Boolean {
        return time == "boot"
    }

    fun toDjsSchedule(): DjsSchedule {
        return DjsSchedule(profile.uid, isEnabled, time, executeOnce, executeOnBoot, getCommand())
    }

    data class Time(val hour: Int, val minute: Int)
}
