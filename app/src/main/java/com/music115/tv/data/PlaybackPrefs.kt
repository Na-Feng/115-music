package com.music115.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.music115.tv.data.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedTrackRef(
    val pc: String,
    val title: String,
    val artist: String,
    val ext: String,
    val display: String,
)

@Serializable
data class SavedQueue(
    val title: String,
    val tracks: List<SavedTrackRef>,
    val index: Int,
    val positionMs: Long,
)

class PlaybackPrefs(private val context: Context, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyQuality = stringPreferencesKey("quality_mode")
    private val keyQueue = stringPreferencesKey("saved_queue")
    private val keyMusicCid = stringPreferencesKey("music_cid")
    private val keyMusicName = stringPreferencesKey("music_name")

    /** 音质偏好常驻内存，读多写少 */
    var qualityMode: QualityMode = QualityMode.LOSSLESS_FIRST
        private set

    init {
        runBlocking {
            val raw = context.appDataStore.data.first()[keyQuality]
            qualityMode = raw?.let {
                try {
                    QualityMode.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: QualityMode.LOSSLESS_FIRST
        }
    }

    fun setQualityMode(mode: QualityMode) {
        qualityMode = mode
        scope.launch {
            context.appDataStore.edit { it[keyQuality] = mode.name }
        }
    }

    suspend fun savedQueue(): SavedQueue? {
        val raw = context.appDataStore.data.first()[keyQueue] ?: return null
        return try {
            json.decodeFromString<SavedQueue>(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** 用户设定的音乐库文件夹（引导设置，存 cid + 名称） */
    suspend fun musicFolder(): FolderInfo? {
        val data = context.appDataStore.data.first()
        val cid = data[keyMusicCid] ?: return null
        val name = data[keyMusicName] ?: "音乐"
        return FolderInfo(cid = cid, name = name, pickcode = "")
    }

    suspend fun setMusicFolder(folder: FolderInfo) {
        context.appDataStore.edit {
            it[keyMusicCid] = folder.cid
            it[keyMusicName] = folder.name
        }
    }

    suspend fun clearMusicFolder() {
        context.appDataStore.edit {
            it.remove(keyMusicCid)
            it.remove(keyMusicName)
        }
    }

    suspend fun saveQueue(queue: SavedQueue) {
        context.appDataStore.edit { it[keyQueue] = json.encodeToString(queue) }
    }

    suspend fun clearQueue() {
        context.appDataStore.edit { it.remove(keyQueue) }
    }
}
