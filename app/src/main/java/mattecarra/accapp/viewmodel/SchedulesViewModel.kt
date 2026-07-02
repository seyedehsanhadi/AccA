package mattecarra.accapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.database.AccaRoomDatabase
import mattecarra.accapp.database.ScheduleDao
import mattecarra.accapp.djs.Djs
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.models.ScheduleProfile
import mattecarra.accapp.models.Schedule
import mattecarra.accapp.utils.LogExt

class SchedulesViewModel(application: Application) : AndroidViewModel(application) {
    val schedules = MutableLiveData<List<Schedule>>()

    private val mSchedulesDao: ScheduleDao
    init {
        val accaDatabase = AccaRoomDatabase.getDatabase(application)
        mSchedulesDao = accaDatabase.scheduleDao()

        viewModelScope.launch {
            try { refreshSchedules() } catch (e: Exception) { Log.e(TAG, "init refresh failed: ${e.message}") }
        }
    }

    // The daemon deletes a fired run-once schedule from its config on its own; without a
    // re-read the UI keeps showing it forever (field report: "the runonce task will not
    // delete itself"). The fragment calls this on every resume so the list always reflects
    // what DJS actually holds.
    fun refresh() = viewModelScope.launch {
        refreshSchedules()
    }

    // DJS is the source of truth: every DB mutation happens only AFTER the corresponding djsc
    // op succeeds, and everything is wrapped so a DJS/shell failure can never silently kill the
    // coroutine (frozen list) or leave the DB and DJS diverged with orphan entries.
    private suspend fun refreshSchedules() {
        try {
            val newSchedules =
                Djs.instance.list()
                    .map { djsSchedule ->
                        withContext(Dispatchers.IO) {
                            async {
                                val scheduleProfile = getScheduleProfileById(djsSchedule.scheduleProfileId)
                                if (scheduleProfile != null)
                                    Schedule(djsSchedule.isEnabled, djsSchedule.time, djsSchedule.executeOnce, djsSchedule.executeOnBoot, scheduleProfile)
                                else {
                                    try { Djs.instance.deleteById(djsSchedule.scheduleProfileId) } catch (e: Exception) {}
                                    null
                                }
                            }
                        }
                    }.mapNotNull { it.await() }
            schedules.postValue(newSchedules)
        } catch (e: Exception) {
            // DJS unavailable / parse error -> keep the last list and log; never freeze or crash.
            Log.e(TAG, "refreshSchedules failed: ${e.message}")
        }
    }

    fun addSchedule(scheduleName: String, time: String, executeOnce: Boolean, executeOnBoot: Boolean, profile: AccConfig) = viewModelScope.launch {
        try {
            val id = insertScheduleProfile(ScheduleProfile(0, scheduleName, profile))
            val ok = Djs.instance.append(
                Schedule(true, time, executeOnce, executeOnBoot, ScheduleProfile(id, scheduleName, profile)).toDjsSchedule()
            )
            if (!ok) mSchedulesDao.deleteById(id)   // roll back the orphan DB row if the DJS write failed
            refreshSchedules()
        } catch (e: Exception) { Log.e(TAG, "addSchedule failed: ${e.message}") }
    }

    fun editSchedule(id: Int, scheduleName: String, isEnabled: Boolean, time: String, executeOnce: Boolean, executeOnBoot: Boolean, profile: AccConfig) = viewModelScope.launch {
        try {
            val scheduleProfile = ScheduleProfile(id, scheduleName, profile)
            val ok = Djs.instance.edit(
                Schedule(isEnabled, time, executeOnce, executeOnBoot, scheduleProfile).toDjsSchedule()
            )
            if (ok) mSchedulesDao.update(scheduleProfile)   // keep DB in step with DJS only on success
            else LogExt().e(TAG, "editSchedule: DJS edit returned false for id=$id; DB left unchanged")
            refreshSchedules()
        } catch (e: Exception) { Log.e(TAG, "editSchedule failed: ${e.message}") }
    }

    fun removeSchedule(schedule: Schedule) = viewModelScope.launch {
        try {
            val ok = Djs.instance.delete(schedule.toDjsSchedule())
            if (ok) mSchedulesDao.deleteById(schedule.profile.uid)   // delete DB only once the DJS entry is gone
            else LogExt().e(TAG, "removeSchedule: DJS delete returned false for uid=${schedule.profile.uid}; DB left unchanged")
            refreshSchedules()
        } catch (e: Exception) { Log.e(TAG, "removeSchedule failed: ${e.message}") }
    }

    companion object { private const val TAG = "SchedulesViewModel" }

    private suspend fun insertScheduleProfile(schedule: ScheduleProfile): Int {
        return mSchedulesDao.insert(schedule).toInt()
    }

    private fun deleteScheduleProfile(id: Int) = viewModelScope.launch {
        mSchedulesDao.deleteById(id)
    }

    private fun updateScheduleProfile(schedule: ScheduleProfile) = viewModelScope.launch {
        mSchedulesDao.update(schedule)
    }

    suspend fun getScheduleProfileById(id: Int): ScheduleProfile? {
        return mSchedulesDao.getScheduleById(id)
    }
}
