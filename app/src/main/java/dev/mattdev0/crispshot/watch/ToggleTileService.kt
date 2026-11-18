package dev.mattdev0.crispshot.watch

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.mattdev0.crispshot.App
import dev.mattdev0.crispshot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile mirroring and toggling Auto-HD. */
class ToggleTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        tileScope.launch {
            val app = application as App
            val enabled = app.settings.isEnabled()
            if (enabled && !ScreenshotJob.isScheduled(this@ToggleTileService)) {
                ScreenshotJob.schedule(this@ToggleTileService)
            }
            applyTileState(enabled)
        }
    }

    override fun onClick() {
        super.onClick()
        tileScope.launch {
            val app = application as App
            val next = !app.settings.isEnabled()
            app.settings.setEnabled(next)
            if (next) {
                ScreenshotJob.schedule(this@ToggleTileService)
            } else {
                ScreenshotJob.cancel(this@ToggleTileService)
            }
            applyTileState(next)
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun applyTileState(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (enabled) R.string.tile_on else R.string.tile_off)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        fun requestSync(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, ToggleTileService::class.java),
                )
            }
        }
    }
}
