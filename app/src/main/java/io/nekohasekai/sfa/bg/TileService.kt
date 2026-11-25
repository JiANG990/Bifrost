package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.graphics.drawable.Icon
import io.nekohasekai.sfa.R
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.constant.Status

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)

    override fun onServiceStatusChanged(status: Status) {
        qsTile?.apply {
            state =
                when (status) {
                    Status.Started -> Tile.STATE_ACTIVE
                    Status.Stopped -> Tile.STATE_INACTIVE
                    else -> Tile.STATE_UNAVAILABLE
                }
            applyIcon(state == Tile.STATE_ACTIVE)
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        applyIcon(connection.status == Status.Started)
        connection.connect()
    }

    /** 打开时用彩色图标，关闭时用灰度图标，方便一眼看出状态。 */
    private fun applyIcon(active: Boolean) {
        qsTile?.apply {
            setIcon(
                Icon.createWithResource(
                    this@TileService,
                    if (active) R.drawable.ic_tile else R.drawable.ic_tile_off,
                ),
            )
            updateTile()
        }
    }

    override fun onStopListening() {
        connection.disconnect()
        super.onStopListening()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        when (connection.status) {
            Status.Stopped -> BoxService.start()
            Status.Started -> BoxService.stop()
            else -> {}
        }
    }
}
