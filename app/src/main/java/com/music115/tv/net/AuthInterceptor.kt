package com.music115.tv.net

import com.music115.tv.auth.CookieRepository
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 统一注入 Cookie + UA + Referer。用 Interceptor 而非 CookieJar：
 * 关键的 WAF 哈希 Cookie 永远不走 Set-Cookie（JS 挑战写入），必须手动全量控制。
 */
class AuthInterceptor(private val repo: CookieRepository) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", Http.APP_USER_AGENT)
            .header("Referer", Http.REFERER)
            .header("Cookie", repo.header())
            .build()

        val response = chain.proceed(request)

        // 收割服务端下发的 Set-Cookie（acw_tc 等），并入仓库
        val setCookies = response.headers.values("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val parsed = setCookies.mapNotNull { sc ->
                val pair = sc.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) pair[0].trim() to pair[1].trim() else null
            }.toMap()
            repo.merge(parsed)
        }
        return response
    }
}
