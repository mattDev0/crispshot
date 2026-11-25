package dev.mattdev0.crispshot.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.mattdev0.crispshot.App
import dev.mattdev0.crispshot.media.BackupPruneWorker
import dev.mattdev0.crispshot.media.RestoreResult
import dev.mattdev0.crispshot.policy.ProcessedSize
import dev.mattdev0.crispshot.watch.ScreenshotJob
import dev.mattdev0.crispshot.watch.ToggleTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App

        lifecycleScope.launch(Dispatchers.IO) {
            BackupPruneWorker.schedule(applicationContext)
        }

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme(primary = BrandIndigoLight, secondary = BrandVioletLight)
                else -> expressiveLightColorScheme().copy(
                    primary = BrandIndigo,
                    secondary = BrandViolet,
                )
            }

            val autoHdEnabled by app.settings.enabledFlow.collectAsStateWithLifecycle(initialValue = false)
            val quickShareEnabled by app.settings.quickShareEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
            val recentEntries by app.settings.recentLogFlow.collectAsStateWithLifecycle(initialValue = emptyList())

            var refreshTick by remember { mutableIntStateOf(0) }
            var setupStatus by remember { mutableStateOf(readSetupStatus()) }
            var jobScheduled by remember { mutableStateOf(ScreenshotJob.isScheduled(this)) }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                setupStatus = readSetupStatus()
                if (autoHdEnabled && !ScreenshotJob.isScheduled(this@MainActivity)) {
                    ScreenshotJob.schedule(this@MainActivity)
                }
                jobScheduled = ScreenshotJob.isScheduled(this@MainActivity)
                refreshTick++
            }

            LaunchedEffect(autoHdEnabled, refreshTick) {
                if (autoHdEnabled && !ScreenshotJob.isScheduled(this@MainActivity)) {
                    ScreenshotJob.schedule(this@MainActivity)
                }
                jobScheduled = ScreenshotJob.isScheduled(this@MainActivity)
            }

            val logItems = remember(recentEntries, refreshTick) {
                recentEntries.map { entry ->
                    LogItemUi(
                        entry = entry,
                        backupAvailable = app.backupStore.hasBackup(entry.mediaId),
                    )
                }
            }

            MaterialExpressiveTheme(
                colorScheme = colors,
                motionScheme = MotionScheme.expressive(),
            ) {
                MainScreen(
                    setup = setupStatus,
                    autoHdEnabled = autoHdEnabled,
                    jobScheduled = jobScheduled,
                    quickShareEnabled = quickShareEnabled,
                    policy = app.policy,
                    recentLog = logItems,
                    snackbarHostState = snackbarHostState,
                    onToggleAutoHd = { next ->
                        scope.launch {
                            app.settings.setEnabled(next)
                            if (next) {
                                ScreenshotJob.schedule(this@MainActivity)
                            } else {
                                ScreenshotJob.cancel(this@MainActivity)
                            }
                            ToggleTileService.requestSync(this@MainActivity)
                            jobScheduled = ScreenshotJob.isScheduled(this@MainActivity)
                        }
                    },
                    onToggleQuickShare = { next ->
                        scope.launch {
                            app.settings.setQuickShareEnabled(next)
                        }
                    },
                    onFixAllFiles = { openAllFilesSettings() },
                    onFixBattery = { openBatteryUnrestrictedSettings() },
                    onOpenWhatsApp = {
                        val waIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
                            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                        if (waIntent != null) {
                            startActivity(waIntent)
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("WhatsApp is not installed")
                            }
                        }
                    },
                    onRestore = { mediaId ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val row = app.mediaStoreRepo.queryById(mediaId)
                                app.inPlaceWriter.restore(mediaId, row)
                            }
                            when (result) {
                                is RestoreResult.Success -> {
                                    app.settings.markRestored(mediaId)
                                    // Record the restored size so the original isn't enlarged again.
                                    app.settings.markProcessed(
                                        mediaId,
                                        ProcessedSize(result.metadata.width, result.metadata.height),
                                    )
                                    refreshTick++
                                    snackbarHostState.showSnackbar(
                                        "Restored ${result.metadata.displayName} (${result.metadata.width}×${result.metadata.height})",
                                    )
                                }
                                is RestoreResult.Failure -> {
                                    snackbarHostState.showSnackbar("Restore failed: ${result.reason}")
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    private fun readSetupStatus(): SetupStatus {
        val allFiles = Environment.isExternalStorageManager()
        val pm = getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        return SetupStatus(
            allFilesGranted = allFiles,
            batteryUnrestricted = battery,
        )
    }

    private fun openAllFilesSettings() {
        val appIntent = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(appIntent) }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryUnrestrictedSettings() {
        val intent = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** Brand colours from the launcher icon, used where dynamic colour isn't available (Android 11). */
private val BrandIndigo = Color(0xFF4F46E5)
private val BrandViolet = Color(0xFF7C3AED)
private val BrandIndigoLight = Color(0xFFA5B4FC)
private val BrandVioletLight = Color(0xFFC4B5FD)
