package com.music115.tv.net

/** 全局唯一 UA：WebView、OkHttp、ExoPlayer 三处必须一致（WAF 挑战 Cookie 绑定 UA） */
object Http {
    const val APP_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val REFERER: String = "https://www.115.com/"
}
