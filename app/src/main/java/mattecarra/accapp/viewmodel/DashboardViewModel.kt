package mattecarra.accapp.viewmodel

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.Preferences
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.models.BatteryInfo
import mattecarra.accapp.R
import mattecarra.accapp.models.DashboardValues
import mattecarra.accapp.utils.LogExt

class DashboardViewModel : ViewModel() {

    private val dashboard: MutableLiveData<DashboardValues> = MutableLiveData()

    fun getDashboardValues(): LiveData<DashboardValues> {
        return dashboard
    }

    init {
        // Dispatchers.IO: the first Acc.instance access does blocking root-shell work, so
        // it must never run on the Main dispatcher (ANR). postValue() because we are off
        // the main thread. try/catch so a transient shell/ACC failure (root slow, daemon
        // restarting, ACC absent) skips one tick instead of killing the poller forever.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (dashboard.hasActiveObservers()) {
                    try {
                        dashboard.postValue(
                            DashboardValues(
                                Acc.instance.getBatteryInfo(),
                                Acc.instance.isAccdRunning()
                            )
                        )
                    } catch (e: Exception) {
                        // ignore this tick; keep polling. Log so the failure is visible.
                        LogExt().e(javaClass.simpleName, "dashboard poll tick failed: ${e.message}")
                    }
                }
                delay(2000)
            }
        }
    }
}
