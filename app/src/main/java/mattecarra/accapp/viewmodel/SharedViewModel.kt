package mattecarra.accapp.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.models.AccConfig
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ProfileUtils

class SharedViewModel(application: Application) : AndroidViewModel(application)
{
    private val mSharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val config: MutableLiveData<Pair<AccConfig?, String?>> = MutableLiveData()

    // Signals that an ACC config apply failed. Exposed read-only via observeApplyFailed()/applyFailed
    // so a UI layer can surface it (e.g. a toast) instead of the failure being silently swallowed.
    private val mApplyFailed: MutableLiveData<Boolean> = MutableLiveData()
    val applyFailed: LiveData<Boolean> get() = mApplyFailed

    init {
        viewModelScope.launch {
            try {
                config.postValue(Pair(Acc.instance.readConfig(), null))
            } catch (ex: Exception) {
                // Return null config and exception
                config.postValue(Pair(null, ex.stackTraceToString()))
            }
        }
    }

    fun loadDefaultConfig()
    {
        LogExt().d(javaClass.simpleName,"loadDefaultConfig()")

        viewModelScope.launch {
            try {
                config.postValue(Pair(Acc.instance.readDefaultConfig(), null))
            } catch (ex: Exception) {
                config.postValue(Pair(null, ex.stackTraceToString()))
            }
        }
    }

    /**
     * Sets an observer for config.
     */
    fun observeConfig(owner: LifecycleOwner, observer: Observer<Pair<AccConfig?, String?>>) {
        config.observe(owner, observer)
    }

    /**
     * Sets an observer for ACC config apply failures (true when an apply did not succeed).
     */
    fun observeApplyFailed(owner: LifecycleOwner, observer: Observer<Boolean>) {
        mApplyFailed.observe(owner, observer)
    }

    /*
    * This method is designed to get a parameter from AccConfig or sAccConfig itself
    * Example:
    * val parameter = getAccConfigValue { it.oneParameter }
    * */
    fun <T> getAccConfigValue(callback: (AccConfig) -> T): T? {
        // config may not be loaded yet, or may have loaded null on a parse failure.
        // Return null instead of force-unwrapping into a crash.
        return config.value?.first?.let(callback)
    }

    /*
    * This method is designed to set a parameter of AccConfig and write on file
    * Example:
    * updateAccConfigValue { config ->
    *   config.oneParameter = 1
    * }
    * */
    suspend fun updateAccConfigValue(operation: (AccConfig) -> Boolean) {
        // No-op if config isn't loaded (or loaded null) rather than crashing on !!.
        val value = config.value?.first ?: return

        if(operation(value)) {
            this.config.postValue(Pair(value, null))
            saveAccConfig(value)
        }
    }

    /*
    * Updates the AccConfig and write on file
    */
    suspend fun updateAccConfig(value: AccConfig)
    {
        LogExt().d(javaClass.simpleName,"updateAccConfig()")
        config.postValue(Pair(value, null))
        saveAccConfig(value)
    }

    /*
    * Saves config on file. It's run in an async thread every time config is updated.
    */
    private suspend fun saveAccConfig(value: AccConfig)
    {
        Acc.instance.updateAccConfig(value, ConfigUpdaterEnable(mSharedPrefs)).also {

            if (it.isSuccessful())
            {
                // Read ACC back after a SUCCESSFUL apply. This is the root fix for the whole
                // "the app shows something ACC is not doing" class of report.
                //
                // Until now the config posted here was what AccA INTENDED to write, and it was
                // only re-read when the apply FAILED. A successful apply is not the same as an
                // exact one: ACC normalizes on write (resume_temp clamping, unit coercion, a
                // rejected control file being dropped, out-of-range values refused), and
                // ConfigUpdaterEnable can suppress whole commands, so sections silently keep
                // their previous on-disk values. AccA then displayed numbers ACC had never
                // stored - which is exactly what users saw as a profile "not taking effect".
                //
                // Reading back makes ACC the single source of truth: the app can only ever show
                // what the daemon actually holds. The cost is one extra root read per
                // user-initiated apply (not per loop, not in the background), which is
                // unmeasurable next to the multi-command apply that just ran.
                val applied = try { Acc.instance.readConfig() } catch (ex: Exception) {
                    LogExt().e("saveAccConfig()", "read-back after apply failed: ${ex.message}")
                    null
                }
                if (applied != null) config.postValue(Pair(applied, null))
            }

            if (!it.isSuccessful())
            {
                // Signal the apply failure: at minimum log it at error level so it is not
                // silently swallowed, and expose it via applyFailed for any in-file observer.
                LogExt().e("saveAccConfig()","ACC apply failed (updateAccConfig returned not successful)")
                mApplyFailed.postValue(true)

                // Re-read the on-disk config so the UI reflects the actual (un-applied) state.
                // Both readConfig() and readDefaultConfig() are root shell calls that can throw;
                // guard the whole chain so a re-throw can't kill this coroutine.
                val currentConfigVal = try
                {
                    LogExt().w("saveAccConfig()","Error in updateAccConfig() -> readConfig()")
                    Acc.instance.readConfig()
                }
                catch (ex: Exception)
                {
                    try
                    {
                        LogExt().e("saveAccConfig()","Error in readConfig() -> readDefaultConfig()")
                        Acc.instance.readDefaultConfig()
                    }
                    catch (ex2: Exception)
                    {
                        LogExt().e("saveAccConfig()","readDefaultConfig() also failed: ${ex2.message}")
                        null
                    }
                }

                config.postValue(Pair(currentConfigVal, null))
            }
        }
    }

    /**
     * Clears the currently selected profile ID from Shared Preferences.
     */
    fun clearCurrentSelectedProfile() {
        ProfileUtils.clearCurrentSelectedProfile(mSharedPrefs)
    }

    /**
     * Sets the profile ID to the profile key in the app's shared preferences.
     * @param profileId ID of the profile selected.
     */
    fun setCurrentSelectedProfile(profileId: Int) {
        ProfileUtils.saveCurrentProfile(profileId, mSharedPrefs)
    }
}