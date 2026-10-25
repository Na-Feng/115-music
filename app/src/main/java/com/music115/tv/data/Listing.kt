package com.music115.tv.data

import com.music115.tv.api.FilesResponse

data class FileInfo(
    val fid: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val pickcode: String,
    val durationSec: Int,
    val parentCid: String,
    /** 同目录同名 .lrc 歌词文件的 pickcode（仅文件夹浏览能拿到，分类/搜索接口不返回歌词文件） */
    val lrcPickcode: String? = null,
)

data class FolderInfo(
    val cid: String,
    val name: String,
    val pickcode: String,
)

sealed interface ListItem

data class FolderItem(val folder: FolderInfo) : ListItem

data class TrackItem(val file: FileInfo) : ListItem

/** 列表展示用：同曲多版本合并为一行（chosen 为默认播放的版本，versions 供播放页切换） */
data class GroupItem(val group: TrackGroup) : ListItem

data class Breadcrumb(val cid: String, val name: String)

data class Listing(
    val cid: String,
    val items: List<ListItem>,
    val breadcrumb: List<Breadcrumb>,
    val total: Int,
)

/** 可播放的音频扩展名（ico 字段取值） */
val AUDIO_EXTS = setOf("mp3", "flac", "wav", "mka", "aac", "m4a", "ogg", "wma")

fun FilesResponse.toListing(cid: String): Listing = Listing(
    cid = cid,
    items = run {
        // 先收集同目录 .lrc 歌词文件：键 = 父目录|基础名（与音频同名，忽略大小写）
        val lrcByKey = HashMap<String, String>()
        for (e in data) {
            if (e.isFolder) continue
            val ext = (e.ico ?: e.n.substringAfterLast('.', "")).lowercase()
            if (ext == "lrc" && e.cid != null) {
                val base = QualityGrouper.parse(e.n, ext).first.lowercase()
                lrcByKey["${e.cid}|$base"] = e.pc
            }
        }
        data.mapNotNull { e ->
            if (e.isFolder) {
                FolderItem(FolderInfo(cid = e.cid ?: "", name = e.n, pickcode = e.pc))
            } else {
                val ext = e.ico ?: e.n.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTS) return@mapNotNull null
                val parent = e.cid ?: cid
                val base = QualityGrouper.parse(e.n, ext).first.lowercase()
                TrackItem(
                    FileInfo(
                        fid = e.fid ?: "",
                        name = e.n,
                        ext = ext,
                        sizeBytes = e.s,
                        pickcode = e.pc,
                        durationSec = e.playLong,
                        parentCid = parent,
                        lrcPickcode = lrcByKey["$parent|$base"],
                    )
                )
            }
        }
    },
    breadcrumb = path.mapNotNull { p ->
        val c = p.cid ?: return@mapNotNull null
        Breadcrumb(c, p.name ?: "")
    },
    total = count,
)
