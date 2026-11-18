package dev.mattdev0.crispshot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mattdev0.crispshot.media.WritePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "crispshot_settings")

/** Single processed screenshot record shown in the UI recent log. */
data class LogEntry(
    val mediaId: Long,
    val displayName: String,
    val oldWidth: Int,
    val oldHeight: Int,
    val newWidth: Int,
    val newHeight: Int,
    val elapsedMs: Long,
    val writePath: WritePath,
    val timestampMs: Long,
    val restored: Boolean = false,
)

/** DataStore-backed preferences: Auto-HD flag, Quick Share toggle, processed IDs (500), and recent log (30). */
class Settings(private val context: Context) {
    val enabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: false
    }

    val quickShareEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_QUICK_SHARE_ENABLED] ?: true
    }

    val processedIdsFlow: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        parseProcessedIds(prefs[KEY_PROCESSED_IDS]).toSet()
    }

    val recentLogFlow: Flow<List<LogEntry>> = context.dataStore.data.map { prefs ->
        parseLogEntries(prefs[KEY_RECENT_LOG])
    }

    suspend fun isEnabled(): Boolean = enabledFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }

    suspend fun isQuickShareEnabled(): Boolean = quickShareEnabledFlow.first()

    suspend fun setQuickShareEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_QUICK_SHARE_ENABLED] = enabled
        }
    }

    suspend fun getLastPruneMs(): Long = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PRUNE_MS] ?: 0L
    }.first()

    suspend fun setLastPruneMs(timestampMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_PRUNE_MS] = timestampMs
        }
    }

    suspend fun getProcessedIds(): Set<Long> = processedIdsFlow.first()

    suspend fun markProcessed(mediaId: Long) {
        context.dataStore.edit { prefs ->
            val list = parseProcessedIds(prefs[KEY_PROCESSED_IDS]).toMutableList()
            list.remove(mediaId)
            list.add(mediaId)
            while (list.size > MAX_PROCESSED_IDS) {
                list.removeAt(0)
            }
            prefs[KEY_PROCESSED_IDS] = list.joinToString(",")
        }
    }

    suspend fun appendLog(entry: LogEntry) {
        context.dataStore.edit { prefs ->
            val current = parseLogEntries(prefs[KEY_RECENT_LOG]).toMutableList()
            current.add(0, entry)
            val trimmed = if (current.size > MAX_LOG_ENTRIES) current.subList(0, MAX_LOG_ENTRIES) else current
            prefs[KEY_RECENT_LOG] = serializeLogEntries(trimmed)
        }
    }

    suspend fun markRestored(mediaId: Long) {
        context.dataStore.edit { prefs ->
            val current = parseLogEntries(prefs[KEY_RECENT_LOG]).map { entry ->
                if (entry.mediaId == mediaId) entry.copy(restored = true) else entry
            }
            prefs[KEY_RECENT_LOG] = serializeLogEntries(current)
        }
    }

    private fun parseProcessedIds(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    private fun parseLogEntries(raw: String?): List<LogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val path = runCatching {
                        WritePath.valueOf(obj.optString("writePath", WritePath.MEDIASTORE.name))
                    }.getOrDefault(WritePath.MEDIASTORE)
                    add(
                        LogEntry(
                            mediaId = obj.getLong("mediaId"),
                            displayName = obj.getString("displayName"),
                            oldWidth = obj.getInt("oldWidth"),
                            oldHeight = obj.getInt("oldHeight"),
                            newWidth = obj.getInt("newWidth"),
                            newHeight = obj.getInt("newHeight"),
                            elapsedMs = obj.getLong("elapsedMs"),
                            writePath = path,
                            timestampMs = obj.getLong("timestampMs"),
                            restored = obj.optBoolean("restored", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeLogEntries(entries: List<LogEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put("mediaId", entry.mediaId)
                    put("displayName", entry.displayName)
                    put("oldWidth", entry.oldWidth)
                    put("oldHeight", entry.oldHeight)
                    put("newWidth", entry.newWidth)
                    put("newHeight", entry.newHeight)
                    put("elapsedMs", entry.elapsedMs)
                    put("writePath", entry.writePath.name)
                    put("timestampMs", entry.timestampMs)
                    put("restored", entry.restored)
                },
            )
        }
        return array.toString()
    }

    companion object {
        const val MAX_PROCESSED_IDS = 500
        const val MAX_LOG_ENTRIES = 30
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_QUICK_SHARE_ENABLED = booleanPreferencesKey("quick_share_enabled")
        private val KEY_LAST_PRUNE_MS = longPreferencesKey("last_prune_ms")
        private val KEY_PROCESSED_IDS = stringPreferencesKey("processed_ids")
        private val KEY_RECENT_LOG = stringPreferencesKey("recent_log")
    }
}
