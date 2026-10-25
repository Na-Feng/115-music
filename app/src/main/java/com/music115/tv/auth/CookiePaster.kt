package com.music115.tv.auth

/** 解析浏览器复制的 Cookie 串："k1=v1; k2=v2; ..." */
object CookiePaster {
    fun parse(raw: String): Map<String, String> =
        raw.split(';', '\n')
            .mapNotNull { piece ->
                val pair = piece.trim().split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) {
                    pair[0].trim() to pair[1].trim()
                } else null
            }
            .toMap()
}
