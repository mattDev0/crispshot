package dev.mattdev0.crispshot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mattdev0.crispshot.R
import dev.mattdev0.crispshot.data.LogEntry
import dev.mattdev0.crispshot.policy.HdPolicy
import java.text.SimpleDateFormat
import java.util.Date

data class SetupStatus(
    val allFilesGranted: Boolean,
    val batteryUnrestricted: Boolean,
)

data class LogItemUi(
    val entry: LogEntry,
    val backupAvailable: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    setup: SetupStatus,
    autoHdEnabled: Boolean,
    jobScheduled: Boolean,
    quickShareEnabled: Boolean,
    policy: HdPolicy,
    recentLog: List<LogItemUi>,
    snackbarHostState: SnackbarHostState,
    onToggleAutoHd: (Boolean) -> Unit,
    onToggleQuickShare: (Boolean) -> Unit,
    onFixAllFiles: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenWhatsApp: () -> Unit,
    onRestore: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AutoHdCard(
                    enabled = autoHdEnabled,
                    jobScheduled = jobScheduled,
                    onToggle = onToggleAutoHd,
                )
            }
            item {
                SetupChecklistCard(
                    setup = setup,
                    onFixAllFiles = onFixAllFiles,
                    onFixBattery = onFixBattery,
                    onOpenWhatsApp = onOpenWhatsApp,
                )
            }
            item {
                PolicyCard(
                    policy = policy,
                    quickShareEnabled = quickShareEnabled,
                    onToggleQuickShare = onToggleQuickShare,
                )
            }
            item {
                Text(
                    text = "Recent (${recentLog.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (recentLog.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nothing yet. Turn on Auto-HD and take a screenshot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(recentLog, key = { "${it.entry.mediaId}_${it.entry.timestampMs}" }) { item ->
                    LogEntryCard(
                        item = item,
                        onRestore = { onRestore(item.entry.mediaId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoHdCard(
    enabled: Boolean,
    jobScheduled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Auto-HD",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                val stateLabel = when {
                    enabled && jobScheduled -> "Active — system-triggered on new screenshots"
                    enabled -> "Enabled — scheduling system trigger…"
                    else -> "Paused. Screenshots stay at their original size"
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun SetupChecklistCard(
    setup: SetupStatus,
    onFixAllFiles: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenWhatsApp: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Setup checklist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ChecklistRow(
                title = "All-files access",
                subtitle = "Lets Crispshot replace each new screenshot with the enlarged copy",
                ok = setup.allFilesGranted,
                buttonLabel = "Allow",
                onAction = onFixAllFiles,
            )
            HorizontalDivider()
            ChecklistRow(
                title = "Unrestricted battery",
                subtitle = "Stops Android delaying Crispshot when you haven't opened it for a while",
                ok = setup.batteryUnrestricted,
                buttonLabel = "Allow",
                onAction = onFixBattery,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = "WhatsApp upload quality",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Settings → Storage and data → Photo upload quality → HD",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onOpenWhatsApp) {
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ok) {
            Button(onClick = onAction) {
                Text(buttonLabel)
            }
        } else {
            Text(
                text = "OK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PolicyCard(
    policy: HdPolicy,
    quickShareEnabled: Boolean,
    onToggleQuickShare: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "WhatsApp only offers HD for images at least ${policy.hdMinLongEdge} px on the long side. " +
                    "New screenshots are enlarged pixel-for-pixel so HD unlocks, and WhatsApp sends " +
                    "them at their original resolution.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Originals are kept for 7 days. Tap Restore in the log to undo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Laptop screenshots",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Also enlarge screenshots received with Quick Share",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = quickShareEnabled,
                    onCheckedChange = onToggleQuickShare,
                )
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    item: LogItemUi,
    onRestore: () -> Unit,
) {
    val entry = item.entry
    val locale = LocalConfiguration.current.locales[0]
    val timeText = remember(entry.timestampMs, locale) {
        SimpleDateFormat("HH:mm:ss", locale).format(Date(entry.timestampMs))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.oldWidth}×${entry.oldHeight} → ${entry.newWidth}×${entry.newHeight} (${entry.elapsedMs} ms)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "$timeText · ${entry.writePath.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                entry.restored -> Text(
                    text = "Restored",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                item.backupAvailable -> OutlinedButton(onClick = onRestore) {
                    Text("Restore")
                }
                else -> Text(
                    text = "Expired",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
