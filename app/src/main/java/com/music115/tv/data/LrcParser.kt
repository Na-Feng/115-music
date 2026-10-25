package com.music115.tv.data

data class LrcLine(val timeMs: Long, val text: String)

/**
 * 歌词内容：syncedLines 非空 = 带 [mm:ss] 时间戳，可随进度高亮滚动；
 * 否则 staticText = 无时间戳的纯文本，整段平铺显示。
 */
data class Lyrics(
    val syncedLines: List<LrcLine>? = null,
    val staticText: String? = null,
) {
    companion object {
        /** 按内容自动判别：解析出时间行就同步显示，否则当静态文本 */
        fun fromText(text: String): Lyrics {
            val lines = LrcParser.parse(text)
            return if (lines.isNotEmpty()) Lyrics(syncedLines = lines) else Lyrics(staticText = text.trim())
        }
    }
}

/** LRC 歌词解析：支持多时间标签、[offset:±ms] 全局偏移、逐字时间标签剥离 */
object LrcParser {

    private val timeTag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val wordTag = Regex("<\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?>")

    fun parse(text: String): List<LrcLine> {
        var offsetMs = 0L
        val out = mutableListOf<LrcLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[offset:") && line.endsWith("]")) {
                offsetMs = line.substringAfter(':').substringBefore(']').toLongOrNull() ?: 0L
                continue
            }
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue // [ti:] 等元数据行
            val content = wordTag.replace(line.substring(tags.last().range.last + 1), "").trim()
            if (content.isEmpty()) continue
            for (t in tags) {
                val min = t.groupValues[1].toLong()
                val sec = t.groupValues[2].toLong()
                val fracStr = t.groupValues[3].ifEmpty { "0" }
                val frac = when (fracStr.length) {
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1000 + frac - offsetMs, content))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
