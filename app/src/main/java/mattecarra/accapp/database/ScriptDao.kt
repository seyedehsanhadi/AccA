package mattecarra.accapp.database

import androidx.lifecycle.LiveData
import androidx.room.*
import mattecarra.accapp.models.AccaScript

@Dao
interface ScriptDao
{
    @Insert
    suspend fun insert(AccaScript: AccaScript)

    @Update
    suspend fun update(AccaScript: AccaScript)

    @Delete
    suspend fun delete(AccaScript: AccaScript)

    @Query("SELECT * FROM scripts_table WHERE scName == :name")
    fun getScriptByName(name: String): List<AccaScript>

    @Query("SELECT * FROM scripts_table WHERE uid == :id")
    suspend fun getScriptById(id: Int): AccaScript?

    @Query("DELETE FROM scripts_table")
    suspend fun deleteAll()

    // scOrder is the user's own arrangement; uid DESC is only the tie-break, so rows written
    // before the column existed (all backfilled to -uid) keep the exact order they had.
    @Query("SELECT * FROM scripts_table ORDER BY scOrder ASC, uid DESC")
    suspend fun getScripts(): List<AccaScript>

    @Query("SELECT * FROM scripts_table ORDER BY scOrder ASC, uid DESC")
    fun getAllScripts(): LiveData<List<AccaScript>>

    // Reorder writes the whole visible list in one transaction. Doing it per-row would emit a
    // LiveData update per write, and the list would visibly shuffle under the user's finger.
    @Update
    suspend fun updateAll(scripts: List<AccaScript>)

    // New and copied scripts go to the TOP, which is where they appeared before this column
    // existed. Returns null on an empty table, so callers default to 0.
    @Query("SELECT MIN(scOrder) FROM scripts_table")
    suspend fun getMinOrder(): Int?
}