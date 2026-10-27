package com.music115.tv.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 歌词文本编码自动识别：BOM → 合法 UTF-8 → GB18030/GBK。
 * 国内歌词（.lrc、ID3 编码 0 的 USLT 等）大量使用 GBK，按 UTF-8 解码会出现「�」方块。
 */
object TextDecoder {

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val b = bytes.copyOfRange(offset, offset + length)
        // BOM
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        strict(b, Charsets.UTF_8)?.let { return it }
        for (name in arrayOf("GB18030", "GBK")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            strict(b, cs)?.let { return it }
        }
        // 都不完全合法：退回 UTF-8（替换非法字节）
        return String(b, Charsets.UTF_8)
    }

    /** 严格解码：遇到非法字节返回 null */
    private fun strict(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
