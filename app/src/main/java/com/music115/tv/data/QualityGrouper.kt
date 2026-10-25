package com.music115.tv.data

/** 音质偏好：LOSSLESS_FIRST 同曲去重取最优；MP3_ONLY 只要 MP3；ALL 全部入队 */
enum class QualityMode { LOSSLESS_FIRST, MP3_ONLY, ALL }

data class TrackGroup(
    val displayName: String,
    val chosen: FileInfo,
    /** 组内全部版本，按音质从高到低（用于解码失败降级） */
    val versions: List<FileInfo>,
    /** 同目录同名 .lrc 歌词文件的 pickcode（组内版本共用一份歌词） */
    val lrcPickcode: String? = null,
)

/**
 * 文件名解析与同曲分组。
 * 例："囚鸟 - 彭羚 (臻品母带).flac" → base="囚鸟 - 彭羚", tag="臻品母带"。
 * 分组键 = 父目录 + 基础名（跨目录同名歌不合并）。
 */
object QualityGrouper {

    private val bracketSuffix = Regex(
        "\\s*[\\(\\[](?:\\s*)(臻品母带|超清母带|超品母带|母带|Hi-Res|SQ|320kbps|2000kbps)(?:\\s*)[\\)\\]]\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val dashSuffix = Regex("\\s*-\\s*(320|2000)\\s*$", RegexOption.IGNORE_CASE)

    /** 返回 (基础名, 质量标签) */
    fun parse(fileName: String, ext: String): Pair<String, String> {
        var base = fileName.removeSuffix(".$ext")
        var tag = ""
        bracketSuffix.find(base)?.let { m ->
            tag = m.groupValues[1]
            base = base.substring(0, m.range.first)
        }
        dashSuffix.find(base)?.let { m ->
            tag = "${m.groupValues[1]}kbps"
            base = base.substring(0, m.range.first)
        }
        return base.trim() to tag
    }

    private fun score(ext: String, tag: String): Int = when {
        ext == "flac" && tag.contains("母带") -> 5
        ext == "flac" -> 4
        ext == "wav" -> 3
        ext == "mka" -> 2
        else -> 1
    }

    /** 同曲去重：基础名相同的版本合成一组，chosen = 音质分最高的版本（默认播放） */
    fun groupAll(files: List<FileInfo>): List<TrackGroup> {
        val byKey = LinkedHashMap<String, MutableList<FileInfo>>()
        for (f in files) {
            val (base, _) = parse(f.name, f.ext)
            byKey.getOrPut("${f.parentCid}|$base") { mutableListOf() }.add(f)
        }
        return byKey.map { (_, versions) ->
            val sorted = versions.sortedByDescending { v -> score(v.ext, parse(v.name, v.ext).second) }
            val chosen = sorted.first()
            TrackGroup(
                displayName = parse(chosen.name, chosen.ext).first,
                chosen = chosen,
                versions = sorted,
                lrcPickcode = chosen.lrcPickcode,
            )
        }
    }

    /** 版本标签（播放页音质按钮用）："母带FLAC" / "MP3" */
    fun versionLabel(f: FileInfo): String {
        val tag = parse(f.name, f.ext).second
        return if (tag.isEmpty()) f.ext.uppercase() else "$tag${f.ext.uppercase()}"
    }
}
