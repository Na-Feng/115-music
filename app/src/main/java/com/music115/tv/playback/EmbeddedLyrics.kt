package com.music115.tv.playback

import com.music115.tv.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * 音频内嵌歌词提取：只读文件头部（标签都在文件开头，配合 CDN 的 Range 支持，
 * 不必下载整首歌）。支持 MP3 的 ID3v2 USLT 帧与 FLAC 的 Vorbis 注释（LYRICS 键），
 * 内容多为 LRC 文本，也可能是无时间戳的纯文本，由上层 Lyrics.fromText 判别。
 */
object EmbeddedLyrics {

    private const val MAX_HEAD_BYTES = 4 * 1024 * 1024
    private val textCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 32
    }

    suspend fun extract(locator: ServiceLocator, pickcode: String): String? {
        synchronized(textCache) { textCache[pickcode]?.let { return it } }
        return withContext(Dispatchers.IO) {
            try {
                val url = locator.api.downloadUrl(pickcode).fileUrl ?: return@withContext null
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=0-${MAX_HEAD_BYTES - 1}")
                    .build()
                locator.okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val head = readHead(resp.body?.byteStream() ?: return@withContext null)
                    val text = fromBytes(head)
                    if (text != null) synchronized(textCache) { textCache[pickcode] = text }
                    text
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readHead(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < MAX_HEAD_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_HEAD_BYTES - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    fun fromBytes(data: ByteArray): String? =
        if (startsWith(data, "ID3")) fromId3(data)
        else if (startsWith(data, "fLaC")) fromFlac(data)
        else null

    private fun startsWith(data: ByteArray, magic: String): Boolean {
        if (data.size < magic.length) return false
        for (i in magic.indices) if (data[i] != magic[i].code.toByte()) return false
        return true
    }

    // ---- ID3v2.3/2.4 USLT ----

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun fromId3(data: ByteArray): String? {
        if (data.size < 10) return null
        val major = data[3].toInt() and 0xFF
        if (major < 3) return null
        val tagEnd = minOf(data.size, 10 + syncsafe(data, 6))
        val flags = data[5].toInt() and 0xFF
        var pos = 10
        if ((flags and 0x40) != 0 && pos + 4 <= tagEnd) {
            // 扩展头：v2.3 的 size 字段含自身，v2.4 不含
            pos += if (major == 4) syncsafe(data, pos) else be32(data, pos)
        }
        var best: String? = null
        while (pos + 10 <= tagEnd) {
            val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
            if (!Regex("[A-Z0-9]{4}").matches(frameId)) break
            val fsize = if (major == 4) syncsafe(data, pos + 4) else be32(data, pos + 4)
            if (fsize <= 0 || pos + 10 + fsize > tagEnd) break
            if (frameId == "USLT") {
                val text = parseUslt(data, pos + 10, fsize)
                if (text != null && (best == null || text.length > best.length)) best = text
            }
            pos += 10 + fsize
        }
        return best
    }

    /** USLT 内容：encoding(1) + language(3) + 描述符(按编码终止) + 歌词文本 */
    private fun parseUslt(data: ByteArray, off: Int, len: Int): String? {
        if (len < 5) return null
        var p = off
        val enc = data[p].toInt()
        p += 1 + 3 // encoding + language
        val end = off + len
        val wide = enc == 1 || enc == 2
        if (wide) {
            while (p + 1 < end && !(data[p].toInt() == 0 && data[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && data[p].toInt() != 0) p++
            p++
        }
        if (p >= end) return null
        val textBytes = data.copyOfRange(p, end)
        val text = when (enc) {
            1 -> runCatching { String(textBytes, Charsets.UTF_16) }.getOrNull()
            2 -> runCatching { String(textBytes, Charsets.UTF_16BE) }.getOrNull()
            3 -> String(textBytes, Charsets.UTF_8)
            // 编码 0 名义上是 ISO-8859-1，但中文文件的 USLT 实际常用 GBK 存储
            else -> {
                val pureAscii = textBytes.all { it.toInt() in 0..127 }
                runCatching {
                    if (pureAscii) String(textBytes, Charsets.ISO_8859_1)
                    else String(textBytes, charset("GBK"))
                }.getOrNull() ?: String(textBytes, Charsets.ISO_8859_1)
            }
        } ?: return null
        return text.trim().takeIf { it.isNotEmpty() }
    }

    // ---- FLAC Vorbis 注释 ----

    private fun fromFlac(data: ByteArray): String? {
        var pos = 4 // 跳过 "fLaC"
        while (pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val len = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (type == 4) { // VORBIS_COMMENT
                if (start + len > data.size) return null
                return parseVorbisComments(data, start, len)
            }
            if (last) return null
            pos = start + len
        }
        return null
    }

    private fun parseVorbisComments(data: ByteArray, off: Int, len: Int): String? {
        var p = off
        val end = off + len
        fun le32(): Int {
            if (p + 4 > end) return -1
            val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or ((data[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = le32()
        if (vendorLen < 0 || p + vendorLen > end) return null
        p += vendorLen
        val count = le32()
        if (count < 0) return null
        val keys = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRICS-CHN")
        var best: String? = null
        for (i in 0 until count) {
            val cl = le32()
            if (cl < 0 || p + cl > end) return best
            val comment = String(data, p, cl, Charsets.UTF_8)
            p += cl
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase().replace(" ", "")
                if (key in keys) {
                    val v = comment.substring(eq + 1)
                    if (v.isNotBlank() && (best == null || v.length > best.length)) best = v
                }
            }
        }
        return best
    }
}
