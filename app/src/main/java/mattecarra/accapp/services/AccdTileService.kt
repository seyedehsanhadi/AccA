package mattecarra.accapp.services

import android.annotation.TargetApi
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mattecarra.accapp.R
import mattecarra.accapp.acc.Acc
import mattecarra.accapp.utils.LogExt
import kotlin.coroutines.CoroutineContext

@TargetApi(Build.VERSION_CODES.N)
class AccdTileService: TileService(), CoroutineScope
{
    private val LOG_TAG = "AccdTileService"

    // Serializes start/stop so rapid taps don't interleave.
    private val tileLock = Any()

    protected lateinit var job: Job
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogExt().e(LOG_TAG, "Coroutine error: ${throwable.message}")
    }
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main + exceptionHandler

    override fun onCreate()
    {
        super.onCreate()
        job = Job()
    }

    override fun onDestroy()
    {
        super.onDestroy()
        job.cancel()
    }

    override fun onClick()
    {
        super.onClick()

        launch {

            val accdRunning = try {
                Acc.instance.isAccdRunning()
            } catch (e: Exception) {
                LogExt().e(LOG_TAG, "isAccdRunning failed: ${e.message}")
                return@launch
            }
            _updateTile(!accdRunning)

            //This will give the user the feeling that his actions are handled immediately. I hope no one will spam on the button.
            //otherwise this enable/disable cycle will take forever
            //I've considered to disable the tile while it's updating but it doesn't look great and google is not doing either with wifi.
            // Synchronize on a real service-owned lock (not the lambda receiver) so
            // rapid taps actually serialize start/stop.
            synchronized(tileLock)
            {
                if (accdRunning)
                {
                    // qsTile can be null if the tile unbound; guard against NPE.
                    val tile = qsTile
                    tile?.label = getString(R.string.wait) //stop deamon a bit, so I moved _updateTile before that
                    tile?.updateTile()

                    //TODO add a mutex instead of relaunching the coroutine
                    launch {
                        try {
                            Acc.instance.abcStopDaemon()
                        } catch (e: Exception) {
                            LogExt().e(LOG_TAG, "abcStopDaemon failed: ${e.message}")
                        }
                        tile?.label = getString(R.string.tile_acc_disabled)
                        tile?.updateTile()
                    }

                } else launch {
                    try {
                        Acc.instance.abcStartDaemon()
                    } catch (e: Exception) {
                        LogExt().e(LOG_TAG, "abcStartDaemon failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onTileRemoved()
    {
        super.onTileRemoved()

        launch {
            Acc.instance.abcStartDaemon()
            // Do something when the user removes the Tile
        }
    }

    override fun onTileAdded()
    {
        super.onTileAdded()
        updateTile()
        // Do something when the user add the Tile
    }

    override fun onStartListening()
    {
        super.onStartListening()
        // Previously this was gated behind READ_EXTERNAL_STORAGE, which the app
        // never declared, so the check always failed and the tile never refreshed.
        // The tile only needs root (handled in updateTile), not storage.
        updateTile()
    }

    private fun updateTile()
    {
        // Shell.rootAccess() may block on the root shell; never run it on the main
        // thread. Probe on IO, then apply the tile state back on Main.
        launch {
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    Shell.rootAccess()
                } catch (e: Exception) {
                    LogExt().e(LOG_TAG, "rootAccess failed: ${e.message}")
                    false
                }
            }

            if (hasRoot) _updateTile() else
            {
                // qsTile is null when the tile isn't currently bound; bail instead of NPE.
                val tile = qsTile ?: return@launch
                tile.label = getString(R.string.tile_acc_no_root)
                tile.state = Tile.STATE_UNAVAILABLE
                tile.icon = Icon.createWithResource(this@AccdTileService, R.drawable.ic_battery_charging_full)
                tile.updateTile() // you need to call this method to apply changes
            }
        }
    }

    private fun _updateTile(accdRunning: Boolean? = null, charging: Boolean? = null)
    {
        launch {
            val mAccdRunning = accdRunning?: Acc.instance.isAccdRunning()
            val mCharging = charging ?: Acc.instance.isBatteryCharging()

            LogExt().d(LOG_TAG, "_updateTile $mAccdRunning $mCharging")

            // qsTile is null when the tile isn't currently bound; bail instead of NPE.
            val tile = qsTile ?: return@launch
            tile.label = getString(if(mAccdRunning) R.string.tile_acc_enabled else R.string.tile_acc_disabled)
            tile.state = if(mAccdRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

            tile.icon = Icon.createWithResource(this@AccdTileService,
                    if(mAccdRunning)
                        if(mCharging) R.drawable.ic_battery_charging_80
                        else R.drawable.ic_battery_80
                    else
                        if(mCharging) R.drawable.ic_battery_charging_full
                        else R.drawable.ic_battery_full
            )
            tile.updateTile() // you need to call this method to apply changes
        }
    }
}