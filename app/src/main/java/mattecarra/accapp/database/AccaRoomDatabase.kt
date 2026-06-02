package mattecarra.accapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mattecarra.accapp.models.*

@Database(entities = [AccaProfile::class, ScheduleProfile::class, AccaScript::class], version = 16)
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
                database.execSQL("INSERT INTO scripts_table (scName, scDescription, scBody, scOutput, scExitCode) VALUES (\"CoolDown Temp after 40%\", \"temperature=(cooldown_temp max_temp max_temp_pause shutdown_temp)\", \"acca -s cooldown_temp=40 max_temp=45 max_temp_pause=90\", \"\", 0);");
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

        fun getDatabase(context: Context): AccaRoomDatabase
        {
            val tempInstance = INSTANCE
            if (tempInstance != null) return tempInstance

            synchronized(this) {
                // Create database instance here
                INSTANCE =
                    Room.databaseBuilder(context.applicationContext, AccaRoomDatabase::class.java, DATABASE_NAME)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                        .addCallback(object : Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                prepopulateDb(getDatabase(context))
                            }
                        }).build()

                return INSTANCE as AccaRoomDatabase
            }
        }

        private fun prepopulateDb(db: AccaRoomDatabase) = CoroutineScope(Dispatchers.Default).launch {

            db.profileDao().insert(
                AccaProfile(0, "Default Custom",
                    AccConfig(
                        configCapacity = AccConfig.ConfigCapacity(5, 70, 80),
                        configTemperature = AccConfig.ConfigTemperature(40, 45, 40)
                    ),
                    ProfileEnables(),
                )
            )

            db.profileDao().insert(
                AccaProfile(0, "Charge to 90%",
                    AccConfig(
                        configCapacity = AccConfig.ConfigCapacity(5, 85, 90),
                        configTemperature = AccConfig.ConfigTemperature(40, 45, 40)
                    ),
                    ProfileEnables()
                )
            )

            db.profileDao().insert(
                AccaProfile(0, "Cool down after 60%",
                    AccConfig(
                        configCapacity = AccConfig.ConfigCapacity(5, 70, 80),
                        configTemperature = AccConfig.ConfigTemperature(40, 45, 40),
                        configCoolDown = AccConfig.ConfigCoolDown(60, 50, 10)
                    ),
                    ProfileEnables(eCoolDown = true)
                )
            )

            db.scriptsDao().insert(AccaScript(0, "CoolDown Temp after 40%",
                "temperature=(cooldown_temp max_temp resume_temp shutdown_temp)",
                "acca -s cooldown_temp=40 max_temp=45 resume_temp=40",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Charge to 90%",
                "capacity=(shutdown_capacity cooldown_capacity resume_capacity pause_capacity capacity_mask)",
                "acca -s shutdown_capacity=10 resume_capacity=85 pause_capacity=90",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Reset current config",
                "-s|--set r|--reset Restore default config",
                "acca -s --reset",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Print current config",
                "-s|--set e.g., acc -s",
                "acca -s",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Scan charging switches (fast)",
                "Ranked switch scan; finds which switch actually stops charging (prints BEST=)",
                "sh /data/adb/vr25/acc/acc-switch-scan.sh",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Scan & lock charging switch",
                "Finds the switch that stops charging and locks it; charging then holds at your limit.",
                "sh /data/adb/vr25/acc/acc-switch-scan.sh --apply",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "List charging switches",
                "-s|--set s:|chargingSwitch:  e.g., acc -s s:",
                "acca -s s:",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Disable charging",
                "-d|--disable [#%, #s, #m or #h (optional)]",
                "acca -d",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Enable charging",
                "-e|--enable [#%, #s, #m or #h (optional)]",
                "acca -e",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Battery Info",
                "-i|--info [case insensitive egrep regex (default: \".\")]",
                "acca -i",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "ACC Version",
                "-v|--version  Print acc version and version code",
                "acca -v",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Idle above limit: ON (default)",
                "allow_idle_above_pcap=true: battery may rest (idle/bypass) above your charge limit. ACC default.",
                "acca -s allow_idle_above_pcap=true",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Idle above limit: OFF",
                "allow_idle_above_pcap=false: never sit above the limit; cycle down to the resume level. Best for forever-plugged 40-60% setups.",
                "acca -s allow_idle_above_pcap=false",
                "", 0)
            )

            db.scriptsDao().insert(AccaScript(0, "Show ACC state (--state)",
                "Prints ACC's machine-readable state snapshot as JSON: level, signed current, status, config as ACC holds it, and the locked switch. Data source for the upcoming diagnostics view.",
                "acca --state",
                "", 0)
            )
        }
    }
}