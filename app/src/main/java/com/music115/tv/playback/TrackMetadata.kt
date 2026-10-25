package com.music115.tv.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music115.tv.data.TrackGroup

object TrackMetadata {

    /** MediaItem URI 方案：One15DataSource 据此识别并解析直链 */
    const val SCHEME = "115"

    fun mediaItem(group: TrackGroup): MediaItem {
        val (title, artist) = splitName(group.displayName)
        return MediaItem.Builder()
            .setMediaId(group.chosen.pickcode)
            .setUri(Uri.parse("${SCHEME}://${group.chosen.pickcode}"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }

    /** "囚鸟 - 彭羚" → (囚鸟, 彭羚)；无分隔符则整体作歌名 */
    fun splitName(display: String): Pair<String, String> {
        val idx = display.indexOf(" - ")
        return if (idx > 0) {
            display.substring(0, idx).trim() to display.substring(idx + 3).trim()
        } else {
            display to ""
        }
    }
}
