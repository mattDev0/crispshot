package dev.mattdev0.crispshot.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item {
                AutoHdHeroCard(
                    enabled = autoHdEnabled,
                    jobScheduled = jobScheduled,
                    onToggle = onToggleAutoHd,
                )
            }
            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Setup checklist")
                SetupChecklistSection(
                    setup = setup,
                    onFixAllFiles = onFixAllFiles,
                    onFixBattery = onFixBattery,
                    onOpenWhatsApp = onOpenWhatsApp,
                )
            }
            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader("How it works")
                PolicySection(
                    policy = policy,
                    quickShareEnabled = quickShareEnabled,
                    onToggleQuickShare = onToggleQuickShare,
                )
            }
            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Recent (${recentLog.size})")
            }
            if (recentLog.isEmpty()) {
                item {
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Text(
                            text = "Nothing yet. Turn on Auto-HD and take a screenshot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = recentLog,
                    key = { _, it -> "${it.entry.mediaId}_${it.entry.timestampMs}" },
                ) { index, item ->
                    LogEntryRow(
                        item = item,
                        index = index,
                        count = recentLog.size,
                        onRestore = { onRestore(item.entry.mediaId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun AutoHdHeroCard(
    enabled: Boolean,
    jobScheduled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "autoHdContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "autoHdContentColor",
    )

    Surface(
        onClick = { onToggle(!enabled) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(enabled)
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = "Auto-HD",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Spacer(Modifier.height(2.dp))
                val stateLabel = when {
                    enabled && jobScheduled -> "Active — system-triggered on new screenshots"
                    enabled -> "Enabled — scheduling system trigger…"
                    else -> "Paused. Screenshots stay at their original size"
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
            ExpressiveSwitch(
                checked = enabled,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun SetupChecklistSection(
    setup: SetupStatus,
    onFixAllFiles: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenWhatsApp: () -> Unit,
) {
    val itemColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ChecklistSegment(
            index = 0,
            count = 3,
            colors = itemColors,
            title = "All-files access",
            subtitle = "Lets Crispshot replace each new screenshot with the enlarged copy",
            ok = setup.allFilesGranted,
            buttonLabel = "Allow",
            onAction = onFixAllFiles,
        )
        ChecklistSegment(
            index = 1,
            count = 3,
            colors = itemColors,
            title = "Unrestricted battery",
            subtitle = "Stops Android delaying Crispshot when you haven't opened it for a while",
            ok = setup.batteryUnrestricted,
            buttonLabel = "Allow",
            onAction = onFixBattery,
        )
        SegmentedListItem(
            onClick = onOpenWhatsApp,
            shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
            colors = itemColors,
            leadingContent = {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            },
            supportingContent = {
                Text(
                    text = "Settings → Storage and data → Photo upload quality → HD",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                FilledTonalButton(
                    onClick = onOpenWhatsApp,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("Open")
                }
            },
        ) {
            Text(
                text = "WhatsApp upload quality",
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
        }
    }
}

@Composable
private fun ChecklistSegment(
    index: Int,
    count: Int,
    colors: androidx.compose.material3.ListItemColors,
    title: String,
    subtitle: String,
    ok: Boolean,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val leading: @Composable () -> Unit = {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
    }
    val supporting: @Composable () -> Unit = {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val trailing: @Composable () -> Unit = {
        if (!ok) {
            Button(
                onClick = onAction,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(buttonLabel)
            }
        } else {
            Text(
                text = "OK",
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    val headline: @Composable () -> Unit = {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
        )
    }

    if (!ok) {
        SegmentedListItem(
            onClick = onAction,
            shapes = shapes,
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = headline,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = headline,
        )
    }
}

@Composable
private fun PolicySection(
    policy: HdPolicy,
    quickShareEnabled: Boolean,
    onToggleQuickShare: (Boolean) -> Unit,
) {
    val itemColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
            colors = itemColors,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "WhatsApp only offers HD for images at least ${policy.hdMinLongEdge} px on the long side. " +
                        "New screenshots are enlarged pixel-for-pixel so HD unlocks, and WhatsApp sends " +
                        "them at their original resolution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Originals are kept for 7 days. Tap Restore in the log to undo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SegmentedListItem(
            onClick = { onToggleQuickShare(!quickShareEnabled) },
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
            colors = itemColors,
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = ToggleableState(quickShareEnabled)
            },
            supportingContent = {
                Text(
                    text = "Also enlarge screenshots received with Quick Share",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailingContent = {
                ExpressiveSwitch(
                    checked = quickShareEnabled,
                    onCheckedChange = null,
                )
            },
        ) {
            Text(
                text = "Laptop screenshots",
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
        }
    }
}

@Composable
private fun LogEntryRow(
    item: LogItemUi,
    index: Int,
    count: Int,
    onRestore: () -> Unit,
) {
    val entry = item.entry
    val locale = LocalConfiguration.current.locales[0]
    val timeText = remember(entry.timestampMs, locale) {
        SimpleDateFormat("HH:mm:ss", locale).format(Date(entry.timestampMs))
    }

    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.padding(
            bottom = if (index < count - 1) ListItemDefaults.SegmentedGap else 0.dp,
        ),
        supportingContent = {
            Column {
                Text(
                    text = "${entry.oldWidth}×${entry.oldHeight} → ${entry.newWidth}×${entry.newHeight} (${entry.elapsedMs} ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$timeText · ${entry.writePath.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                entry.restored -> Text(
                    text = "Restored",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.secondary,
                )
                item.backupAvailable -> FilledTonalButton(
                    onClick = onRestore,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("Restore")
                }
                else -> Text(
                    text = "Expired",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
