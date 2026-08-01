package mattecarra.accapp.models

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "scripts_table")
data class AccaScript(
    @PrimaryKey(autoGenerate = true) var uid: Int, // NEED var for DUPLICATE INSERT
    var scName: String,
    var scDescription: String,
    var scBody: String,
    var scOutput : String,
    var scExitCode: Int,
    // User-controlled sort position. Before this existed the list was ordered by `uid DESC`,
    // i.e. newest first and unchangeable, so re-ordering meant copy-then-delete. Defaulted so
    // every existing construction site keeps compiling; MIGRATION_19_20 backfills it as -uid,
    // which reproduces the old newest-first order exactly on the first run after upgrading.
    var scOrder: Int = 0
) : Serializable
