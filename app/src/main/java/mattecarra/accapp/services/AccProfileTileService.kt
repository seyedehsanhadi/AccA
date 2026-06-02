package mattecarra.accapp.services

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.acc.ConfigUpdaterEnable
import mattecarra.accapp.models.AccaProfile
import mattecarra.accapp.utils.LogExt
import mattecarra.accapp.utils.ProfileUtils
import mattecarra.accapp.viewmodel.ProfilesViewModel
import kotlin.coroutines.CoroutineContext

@TargetApi(Build.VERSION_CODES.N)
class AccProfileTileService: TileService(), CoroutineScope {
    protected lateinit var job: Job
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogExt().e(LOG_TAG, "Coroutine error: ${throwable.message}")
    }
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main + exceptionHandler

    private val LOG_TAG = "AccProfileTileService"
    private lateinit var profilesViewModel: ProfilesViewModel
    // Kept so we can removeObserver in onDestroy and avoid leaking the service.
    private val profilesObserver = Observer<List<AccaProfile>> { updateTile() }

    override fun onCreate()
    {
        super.onCreate()
        job = Job()
        profilesViewModel = ProfilesViewModel(application)
        profilesViewModel.getLiveData().observeForever(profilesObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        profilesViewModel.getLiveData().removeObserver(profilesObserver)
        job.cancel()
    }

    private fun updateTile()
    {
        // qsTile is null when the tile isn't currently bound (observeForever can
        // fire any time); bail instead of NPE-ing on tile.label below.
        val tile = qsTile ?: return
        val profiles = profilesViewModel.getLiveData().value

        if(profiles?.isNotEmpty() == true)
        {
            val profileId = ProfileUtils.getCurrentProfile(PreferenceManager.getDefaultSharedPreferences(this))
            val currProfile = if(profileId != -1) profiles.find { it.uid == profileId } else null

            if(currProfile != null)
            {
                tile.label =  getString(R.string.profile_tile_label, currProfile.profileName)
                tile.state =  Tile.STATE_ACTIVE
            } else {
                tile.label = getString(R.string.profile_not_selected)
                tile.state =  Tile.STATE_INACTIVE
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_battery_charging_80) //use acc icon once ready

        } else {
            tile.label = getString(R.string.no_profiles)
            tile.state =  Tile.STATE_UNAVAILABLE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_battery_charging_full) //use acc icon once ready
        }

        tile.updateTile()
    }

    override fun onTileAdded()
    {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening()
    {
        super.onStartListening()
        updateTile()
    }

    //Get profiles list and increment current profile of one unit.
    override fun onClick()
    {
        super.onClick()

        val mSharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        profilesViewModel.getLiveData().value?.let { profileList ->
            // Guard: an empty list makes indexOfFirst(...) return -1, +1 = 0, then
            // profileList[0] throws IndexOutOfBounds. Nothing to cycle to, so bail.
            if (profileList.isEmpty()) return@let

            val currentProfile = ProfileUtils.getCurrentProfile(mSharedPrefs)

            var index = profileList.indexOfFirst { it.uid ==  currentProfile} + 1
            if(index >= profileList.size) index = 0

            val profile = profileList[index]

            //apply profile
            launch {
                val res = try {
                    Acc.instance.updateAccConfig(profile.accConfig, ConfigUpdaterEnable(mSharedPrefs))
                } catch (e: Exception) {
                    LogExt().e(LOG_TAG, "updateAccConfig failed: ${e.message}")
                    null
                }

                val success = res?.isSuccessful() == true

                //Update tile infos (qsTile may be null if the tile unbound meanwhile)
                qsTile?.let { tile ->
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = if (success) getString(R.string.profile_tile_label, profile.profileName) else getString(R.string.error_occurred)
                    tile.updateTile()
                }

                // Only persist the selection when the profile actually applied,
                // otherwise the tile would advance to a profile that isn't active.
                if (success) ProfileUtils.saveCurrentProfile(profile.uid, mSharedPrefs)
            }
        }
    }
}