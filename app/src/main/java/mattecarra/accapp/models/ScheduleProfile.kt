package mattecarra.accapp.models

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules_table")
data class ScheduleProfile(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    var scheduleName: String,
    @Embedded var accConfig: AccConfig,
    // The gates in force when this schedule was written. Empty = all enabled, which is what
    // every row created before this column existed holds.
    var cueMask: String = ""
)
