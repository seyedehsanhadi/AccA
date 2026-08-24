package mattecarra.accapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mattecarra.accapp.models.*

@Database(entities = [AccaProfile::class, ScheduleProfile::class, AccaScript::class], version = 23)
@TypeConverters(ConfigConverter::class)
abstract class AccaRoomDatabase : RoomDatabase()
{
    abstract fun profileDao(): ProfileDao
    abstract fun scriptsDao(): ScriptDao
    abstract fun scheduleDao(): ScheduleDao

    companion object
    {
        @Volatile
        private var INSTANCE: AccaRoomDatabase? = null

        const val DATABASE_NAME = "acca_database"

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {}
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN prioritizeBatteryIdleMode INTEGER NOT NULL DEFAULT 0");
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS schedules_table (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `configCapacity` TEXT NOT NULL, `configVoltage` TEXT NOT NULL, `configTemperature` TEXT NOT NULL, `configOnBoot` TEXT, `configOnPlug` TEXT, `configCoolDown` TEXT, `configResetUnplugged` INTEGER NOT NULL, `configChargeSwitch` TEXT, `prioritizeBatteryIdleMode` INTEGER NOT NULL)");
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE schedules_table ADD COLUMN `scheduleName` TEXT NOT NULL DEFAULT 'Default schedule'");
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `configResetBsOnPause` INTEGER NOT NULL DEFAULT 0");
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE schedules_table ADD COLUMN `configResetBsOnPause` INTEGER NOT NULL DEFAULT 0");

                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `configCurrMax` INTEGER DEFAULT NULL");
                database.execSQL("ALTER TABLE schedules_table ADD COLUMN `configCurrMax` INTEGER DEFAULT NULL");
            }
        }

        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `configIsAutomaticSwitchingEnabled` INTEGER NOT NULL DEFAULT 1");
                database.execSQL("ALTER TABLE schedules_table ADD COLUMN `configIsAutomaticSwitchingEnabled` INTEGER NOT NULL DEFAULT 1");
            }
        }

        private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS scripts_table (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scName` TEXT NOT NULL, `scDescription` TEXT NOT NULL, `scBody` TEXT NOT NULL, `scOutput` TEXT NOT NULL, `scExitCode` INTEGER NOT NULL)");
            }
        }

        private val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                // Tested!
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"CoolDown Temp after 40%\", \"temperature=(cooldown_temp max_temp resume_temp shutdown_temp)\", \"acca -s cooldown_temp=40 max_temp=45 resume_temp=40\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Charge to 90%\", \"capacity=(shutdown_capacity cooldown_capacity resume_capacity pause_capacity capacity_freeze2)\", \"acca -s shutdown_capacity=10 resume_capacity=85 pause_capacity=90\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Reset current Config\", \"-s|--set r|--reset Restore default config\", \"acca -s r\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Print current config\", \"-s|--set e.g., acc -s\", \"acca -s\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Test charging switches\", \"-t|--test [file] Test charging switches from a file (default: /dev/.vr25/acc/ch-switches)\", \"acca -t\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"List charging switches\", \"-s|--set s:|chargingSwitch: e.g, acc -s s:\", \"acca -s s:\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Disable charging\", \"-d|--disable [#%, #s, #m or #h (optional)]\", \"acca -d\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Enable charging\", \"-e|--enable [#%, #s, #m or #h (optional)]\", \"acca -e\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Battery Info\", \"-i|--info [case insensitive egrep regex (default: .)]\", \"acca -i\", \"\", 0);");
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"ACC Version\", \"-v|--version  Print acc version and version code\", \"acca -v\", \"\", 0);");
            }
        }

        private val MIGRATION_10_11: Migration = object : Migration(10, 11)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
              //database.execSQL("ALTER TABLE profiles_table ADD COLUMN `pEnables` TEXT NOT NULL DEFAULT '"+fromEnables(ProfileEnables())+"'");
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `pEnables` TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `pScripts` TEXT")
            }
        }

        // 1.0.50: add the fast charging-switch scanner scripts. Data-only INSERTs
        // (no schema/table change), same pattern as MIGRATION_9_10, so it cannot
        // change the DB shape and is safe for existing installs.
        private val MIGRATION_11_12: Migration = object : Migration(11, 12)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Scan charging switches (fast)\", \"Ranked switch scan; finds which switch actually stops charging (prints BEST=)\", \"sh /data/adb/vr25/acc/acc-switch-scan.sh\", \"\", 0);")
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Scan & fix charging switch\", \"Runs the fast scan and locks in the best switch (APPLIED=1 on success)\", \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply\", \"\", 0);")
            }
        }

        // 1.0.56: let the user lock a charging METHOD. Rename the existing "Scan & fix"
        // script so it's clear it locks the hold-at-limit method (the default), and add
        // a second one that locks the discharge-cycle method. Data-only INSERT/UPDATE
        // (no schema change), safe for existing installs.
        private val MIGRATION_12_13: Migration = object : Migration(12, 13)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("UPDATE scripts_table SET scName = \"Scan & lock: hold at limit (default)\", scDescription = \"Scans, then LOCKS the switch that holds the battery AT your limit (pcap). Default, longevity-friendly.\" WHERE scBody = \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply\";")
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Scan & lock: discharge-cycle\", \"Scans, then LOCKS the discharge-cycle method (pcap 5): drains to your resume level, recharges to the limit, repeats. Use when battery-idle is off.\", \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply --cycle\", \"\", 0);")
            }
        }

        // 1.1.2: hold-at-limit only. The pcap-5 discharge variant was removed from the
        // daemon (it drained the battery to the resume level), so drop the now-pointless
        // "discharge-cycle" script and rename the remaining one. Data-only DELETE/UPDATE.
        private val MIGRATION_13_14: Migration = object : Migration(13, 14)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("DELETE FROM scripts_table WHERE scBody = \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply --cycle\";")
                database.execSQL("UPDATE scripts_table SET scName = \"Scan & lock charging switch\", scDescription = \"Finds the switch that stops charging and locks it; charging then holds at your limit.\" WHERE scBody = \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply\";")
            }
        }

        // 1.1.5: (a) expose ACC's allow_idle_above_pcap as two one-tap scripts (ACC has no
        // dedicated AccA control for it) -- ON is ACC's own default (battery may rest above the
        // limit), OFF suits forever-plugged setups that cycle down instead. (b) Modernize two
        // stale sample scripts for 2025.x ACC: the temperature sample wrote the legacy
        // max_temp_pause key (renamed to resume_temp; silently dropped now), and the capacity
        // sample labelled the 5th field capacity_freeze2 (now capacity_mask). Data-only
        // INSERT/UPDATE (no schema change), same pattern as MIGRATION_11_12.
        private val MIGRATION_14_15: Migration = object : Migration(14, 15)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Idle above limit: ON (default)\", \"allow_idle_above_pcap=true: battery may rest (idle/bypass) above your charge limit. ACC default.\", \"acca -s allow_idle_above_pcap=true\", \"\", 0);")
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Idle above limit: OFF\", \"allow_idle_above_pcap=false: never sit above the limit; cycle down to the resume level. Best for forever-plugged 40-60% setups.\", \"acca -s allow_idle_above_pcap=false\", \"\", 0);")
                database.execSQL("UPDATE scripts_table SET scDescription = \"temperature=(cooldown_temp max_temp resume_temp shutdown_temp)\", scBody = \"acca -s cooldown_temp=40 max_temp=45 resume_temp=40\" WHERE scName = \"CoolDown Temp after 40%\";")
                database.execSQL("UPDATE scripts_table SET scDescription = \"capacity=(shutdown_capacity cooldown_capacity resume_capacity pause_capacity capacity_mask)\" WHERE scName = \"Charge to 90%\";")
            }
        }

        // 1.1.6-rc2: expose ACC's new state export (`acca --state`) as a one-tap script so it can
        // be run and inspected from the app immediately, ahead of the in-app diagnostics view.
        // Data-only INSERT (no schema change), same pattern as MIGRATION_14_15.
        private val MIGRATION_15_16: Migration = object : Migration(15, 16)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"Show ACC state (--state)\", \"Prints ACC's machine-readable state snapshot as JSON: level, signed current, status, config as ACC holds it, and the locked switch. Data source for the upcoming diagnostics view.\", \"acca --state\", \"\", 0);")
            }
        }

        // 1.1.7-rc3: remove the redundant/confusing "Scan & lock charging switch" script.
        // It locks ACC's first-stopper switch, which conflicts with BOTH the daemon's own
        // automatic switch-lock AND the new verified-switch Apply&Lock card (reliability-ranked,
        // idle-preferring, live-tested). Three "lock a switch" paths gave different outcomes
        // (e.g. input_suspend-drain vs charge_control_limit-idle) and confused users. The daemon
        // auto-locks on its own; the card is the premium path; the diagnostic Test/Scan/List
        // scripts (which never lock) are kept. Data-only DELETE, same pattern as MIGRATION_13_14.
        private val MIGRATION_16_17: Migration = object : Migration(16, 17)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("DELETE FROM scripts_table WHERE scBody = \"sh /data/adb/vr25/acc/acc-switch-scan.sh --apply\";")
            }
        }

        // 1.1.8-rc8: remove the last standalone switch-scan script. Like the --apply
        // variant dropped in 16_17, the bare scanner (sh /data/adb/vr25/acc/acc-switch-scan.sh)
        // fights the daemon's own switch auto-discovery, bypasses the acca PATH-rewrite, and
        // points at a helper that is absent on stock/KSU/APatch installs (dead row). The
        // verified-switch Apply&Lock card is the supported path. Data-only DELETE, same
        // pattern as MIGRATION_16_17.
        private val MIGRATION_17_18: Migration = object : Migration(17, 18)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("DELETE FROM scripts_table WHERE scBody = \"sh /data/adb/vr25/acc/acc-switch-scan.sh\";")
            }
        }

        // 2.0.1-rc10: remove the "Test charging switches" quick-action (acca -t). Same danger that
        // retired the "Test battery idle mode" button in rc6: it STOPS the daemon and runs for
        // minutes (charging uncontrolled), and a force-close SIGKILLs it before the restore runs,
        // leaving a switch cut (no charge till reboot) or the battery overcharged past the limit
        // (A3-reproduced). It duplicates the safe snapshot-restored "Find my charging switch" (AMPS)
        // and the daemon auto-lock. Data-only DELETE, same pattern as MIGRATION_16_17/17_18.
        private val MIGRATION_18_19: Migration = object : Migration(18, 19)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("DELETE FROM scripts_table WHERE scBody = \"acca -t\";")
            }
        }

        // Adds the user-controlled sort position for scripts. Backfilling scOrder = -uid makes
        // "ORDER BY scOrder ASC" reproduce the previous "ORDER BY uid DESC" exactly, so nobody's
        // list visibly jumps on upgrade. Negative values are fine and deliberate: new scripts are
        // inserted at min(scOrder)-1 so they still land at the top like they always did.
        private val MIGRATION_19_20: Migration = object : Migration(19, 20)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("ALTER TABLE scripts_table ADD COLUMN scOrder INTEGER NOT NULL DEFAULT 0;")
                database.execSQL("UPDATE scripts_table SET scOrder = -uid;")
            }
        }

        // Six read-only diagnostics that previously had no route out of a terminal. Every one was
        // RUN against a real rc22 device before being seeded here, so none of them errors, and the
        // descriptions match what the command actually prints (acc -sl, for instance, lists
        // LANGUAGES, not switches, so it is deliberately absent).
        //
        // Nothing here changes charging state. Candidates that did were rejected on purpose:
        //   acc -sb clear   re-enables a node that has already crashed the phone
        //   acc -f 80       overrides the user's limit for a whole cycle
        //   acc -R          resets battery stats, not undoable
        //   acc -D restart  the dashboard already has a RESTART button
        //   acc -s --reset  duplicate of the existing "Reset current Config"
        // A one-tap list is the wrong home for anything you would want a confirmation dialog for.
        //
        // Data-only INSERTs, same pattern as MIGRATION_9_10/11_12, so the schema cannot change.
        // Each row takes MIN(scOrder)-1 so it lands at the top like any new script; they are
        // therefore inserted in REVERSE of the order they should read in.
        private val MIGRATION_20_21: Migration = object : Migration(20, 21)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                val seed = { name: String, desc: String, body: String ->
                    database.execSQL(
                        "INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode, scOrder) " +
                        "VALUES (?, ?, ?, '', 0, (SELECT COALESCE(MIN(scOrder),0)-1 FROM scripts_table));",
                        arrayOf<Any>(name, desc, body)
                    )
                }
                seed("Charger re-kick: status",
                     "Shows whether ACC re-runs charger detection when charging looks stalled. Turn it off if it disturbs fast charging.",
                     "acca -sk")
                seed("Boot-gap cap: status",
                     "The one limit ACC applies before Android starts, closing the window where a reboot at your limit would charge past it. Prints on or off.",
                     "acca --early-cap")
                seed("Collect diagnostic bundle",
                     "Writes ONE file to Download with logs, config and battery state, serial and MAC redacted. This is the file to send when reporting a problem.",
                     "acca --diag")
                seed("Blocked switches (crash list)",
                     "Charging switches that crashed this phone during a scan and are never written again. Also says if ACC has stopped searching for a switch, and how to undo that.",
                     "acca -sb")
                seed("Usable charging switches",
                     "Numbered list of the switches ACC can actually use here, with the active one marked. Pick one with: acca -ss <number>",
                     "acca -ss::")
                seed("Daemon status (version + PID)",
                     "Is the charge-control daemon running, which build, and its process id. The first thing to check when a limit is not being held.",
                     "acca -D")
            }
        }

        // Trim the seeded list to what actually earns a place in a one-tap list.
        //
        // Removed, and why:
        //   ACC Version           the new "Daemon status" prints the version AND the PID
        //   List charging switches  "acca -s s:" lists 30 CANDIDATE nodes; "Usable charging
        //                         switches" (-ss::) lists the ones ACC can really use, numbered
        //   Charge to 90%         silently rewrites your capacity limits with no confirmation
        //   CoolDown Temp...      silently rewrites your temperature limits, same problem
        //   Reset current Config  wipes the config in one tap, no confirmation, no undo
        //   Show ACC state        machine-readable JSON for the diagnostics view, not for reading
        //
        // Matched on name AND body together, so anything the user renamed or edited is THEIRS and
        // survives untouched. Earlier removals here matched on body alone, which would also have
        // deleted a user's edited copy -- and losing user-authored scripts is the exact complaint
        // that prompted the export feature.
        private val MIGRATION_21_22: Migration = object : Migration(21, 22)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                val drop = { name: String, body: String ->
                    database.execSQL(
                        "DELETE FROM scripts_table WHERE scName = ? AND scBody = ?;",
                        arrayOf<Any>(name, body)
                    )
                }
                drop("ACC Version",             "acca -v")
                drop("List charging switches",  "acca -s s:")
                drop("Charge to 90%",           "acca -s shutdown_capacity=10 resume_capacity=85 pause_capacity=90")
                drop("CoolDown Temp after 40%", "acca -s cooldown_temp=40 max_temp=45 resume_temp=40")
                // The reset script exists in two shapes: the original migration seed, and the
                // fresh-install seed which renamed it and switched to the documented --reset form.
                // Match both, or whichever route a given phone was seeded by survives the trim.
                drop("Reset current Config",    "acca -s r")
                drop("Reset current config",    "acca -s --reset")
                drop("Show ACC state (--state)", "acca --state")
            }
        }

        // AccConfig is @Embedded in BOTH profiles_table and schedules_table, so adding
        // configCoolDownCapacity to it adds a column to each. Without this migration Room
        // sees the same version number with a different schema hash and throws on open --
        // and fallbackToDestructiveMigration does NOT cover that case, because it only
        // triggers on a version CHANGE it cannot handle. 101 is ACC's "cool-down off" value,
        // which is what an existing row without the column has always meant.
        private val MIGRATION_22_23: Migration = object : Migration(22, 23)
        {
            override fun migrate(database: SupportSQLiteDatabase)
            {
                database.execSQL("ALTER TABLE profiles_table ADD COLUMN `configCoolDownCapacity` INTEGER NOT NULL DEFAULT 101");
                database.execSQL("ALTER TABLE schedules_table ADD COLUMN `configCoolDownCapacity` INTEGER NOT NULL DEFAULT 101");
            }
        }

        fun getDatabase(context: Context): AccaRoomDatabase
        {
            val tempInstance = INSTANCE
            if (tempInstance != null) return tempInstance

            synchronized(this) {
                // Create database instance here
                INSTANCE =
                    Room.databaseBuilder(context.applicationContext, AccaRoomDatabase::class.java, DATABASE_NAME)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                        // If a migration ever throws, or the on-disk DB is a newer/corrupt
                        // version, REBUILD the DB instead of crashing on every launch -- that
                        // crash is what forced a manual uninstall/reinstall ("blank page until
                        // reinstall"). Worst case loses saved profiles/scripts, which repopulate.
                        .fallbackToDestructiveMigration()
                        .fallbackToDestructiveMigrationOnDowngrade()
                        .addCallback(object : Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                prepopulateDb(getDatabase(context))
                            }
                        }).build()

                return INSTANCE as AccaRoomDatabase
            }
        }

        private fun prepopulateDb(db: AccaRoomDatabase) = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, t -> android.util.Log.e("prepopulateDb", "default-profile insert failed: " + t.message) }).launch {

            // ONE seeded profile, not three.
            //
            // Three shipped profiles ("Default Custom", "Charge to 90%", "Cool down after 60%")
            // filled the list on a fresh install with settings nobody chose, and two of them
            // differ from the first only in numbers a user is going to change anyway. A profile
            // list is the user's own space; seeding it with presets makes their real profiles
            // harder to find and invites applying one by accident.
            //
            // One example is enough to show what a profile is and how to make more. It carries
            // ACC's own shipped defaults so applying it is a no-op rather than a surprise
            // reconfiguration.
            db.profileDao().insert(
                AccaProfile(0, "Default",
                    AccConfig(
                        configCapacity = AccConfig.ConfigCapacity(5, 70, 75),
                        configTemperature = AccConfig.ConfigTemperature(45, 50, 40),
                        // Automatic switch selection OFF in the shipped profile. It defaults to
                        // true in AccConfig, so every seeded profile carried it, and applying one
                        // told ACC to go pick a charging switch by itself. That fights AMPS, which
                        // exists to choose and verify the switch: the profile re-selects, AMPS
                        // re-selects, and the two disagree about which node holds the limit.
                        // A user who wants automatic selection can turn it on; a shipped example
                        // should not silently reconfigure the switch that is already working.
                        configIsAutomaticSwitchingEnabled = false
                    ),
                    ProfileEnables(),
                )
            )

            // The seeded scripts for a FRESH install. This list and MIGRATION_20_21/21_22 must
            // agree: a new phone and an upgraded one should end up with the same set, and they used
            // to drift because this block is not a migration and is easy to forget.
            //
            // Every entry is either a read-only report or an action a user would deliberately
            // choose. Deliberately NOT seeded, and why:
            //   acca -t            stops the daemon for minutes; a force-close SIGKILLs it before
            //                      its restore runs, leaving charging cut or the limit overshot
            //                      (A3-reproduced). "Find my charging switch" does this safely.
            //   -s <presets>       "Charge to 90%" / "CoolDown Temp" silently rewrote the user's
            //                      limits in one tap. The config editor does that properly.
            //   -s --reset         wipes the config with no confirmation and no undo.
            //   -sb clear          re-enables a node that has already crashed the phone.
            //   -f <n>             overrides the user's limit for a whole cycle.
            //   -v                 "Daemon status" already prints the version, with the PID.
            //   -s s:              lists 30 CANDIDATE nodes; "Usable charging switches" lists the
            //                      ones ACC can really use, numbered so -ss <n> can pick one.
            //   --state            machine-readable JSON for the diagnostics view, not for reading.
            //
            // scOrder is explicit so the list reads in this order on a fresh install; the six
            // diagnostics lead because they answer "why is my limit not working".
            val seeded = listOf(
                AccaScript(0, "Daemon status (version + PID)",
                    "Is the charge-control daemon running, which build, and its process id. The first thing to check when a limit is not being held.",
                    "acca -D", "", 0, 0),
                AccaScript(0, "Usable charging switches",
                    "Numbered list of the switches ACC can actually use here, with the active one marked. Pick one with: acca -ss <number>",
                    "acca -ss::", "", 0, 1),
                AccaScript(0, "Blocked switches (crash list)",
                    "Charging switches that crashed this phone during a scan and are never written again. Also says if ACC has stopped searching for a switch, and how to undo that.",
                    "acca -sb", "", 0, 2),
                AccaScript(0, "Collect diagnostic bundle",
                    "Writes ONE file to Download with logs, config and battery state, serial and MAC redacted. This is the file to send when reporting a problem.",
                    "acca --diag", "", 0, 3),
                AccaScript(0, "Boot-gap cap: status",
                    "The one limit ACC applies before Android starts, closing the window where a reboot at your limit would charge past it. Prints on or off.",
                    "acca --early-cap", "", 0, 4),
                AccaScript(0, "Charger re-kick: status",
                    "Shows whether ACC re-runs charger detection when charging looks stalled. Turn it off if it disturbs fast charging.",
                    "acca -sk", "", 0, 5),
                AccaScript(0, "Battery Info",
                    "Level, voltage, current, temperature and charging status as the kernel reports them.",
                    "acca -i", "", 0, 6),
                AccaScript(0, "Print current config",
                    "Every setting ACC is currently holding, in the form it stores them.",
                    "acca -s", "", 0, 7),
                AccaScript(0, "Enable charging",
                    "Release any hold and let the battery charge again.",
                    "acca -e", "", 0, 8),
                AccaScript(0, "Disable charging",
                    "Stop charging now. Charging stays off until you enable it again or the daemon resumes control.",
                    "acca -d", "", 0, 9),
                AccaScript(0, "Idle above limit: ON (default)",
                    "allow_idle_above_pcap=true: battery may rest (idle/bypass) above your charge limit. ACC default.",
                    "acca -s allow_idle_above_pcap=true", "", 0, 10),
                AccaScript(0, "Idle above limit: OFF",
                    "allow_idle_above_pcap=false: never sit above the limit; cycle down to the resume level. Best for forever-plugged 40-60% setups.",
                    "acca -s allow_idle_above_pcap=false", "", 0, 11)
            )
            for (s in seeded) db.scriptsDao().insert(s)
        }
    }
}