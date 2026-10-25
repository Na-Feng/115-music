package com.music115.tv.api

import com.music115.tv.auth.CookieRepository
import com.music115.tv.auth.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 115 网页版内部接口（Cookie 方案，均为实测过的 GET 接口）：
 * - 列表/分类/搜索：webapi.115.com/files（type=3 音频分类，search_value 搜索）
 * - 直链：webapi.115.com/files/download?pickcode=
 */
class Api115(
    private val client: OkHttpClient,
    private val repo: CookieRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun files(
        cid: String,
        offset: Int,
        limit: Int = 40,
        type: Int? = null,
        searchValue: String? = null,
        orderBy: String = "user_ptime",
        ascending: Boolean = false,
    ): FilesResponse {
        // 搜索是独立端点 /files/search：/files?search_value= 会被服务端静默忽略（实测）
        val base = if (searchValue.isNullOrBlank()) "https://webapi.115.com/files" else "https://webapi.115.com/files/search"
        val url = buildString {
            append(base).append("?aid=1&cid=").append(cid)
            append("&o=").append(orderBy)
            append("&asc=").append(if (ascending) 1 else 0)
            append("&offset=").append(offset)
            append("&show_dir=1&limit=").append(limit)
            if (type != null) append("&type=").append(type)
            if (!searchValue.isNullOrBlank()) {
                append("&search_value=").append(URLEncoder.encode(searchValue, "UTF-8"))
            }
            append("&format=json")
        }
        return ensure(getJson(url, FilesResponse.serializer()))
    }

    suspend fun downloadUrl(pickcode: String): DownloadResponse =
        ensure(
            getJson(
                "https://webapi.115.com/files/download?pickcode=$pickcode",
                DownloadResponse.serializer(),
            )
        )

    private fun <T : ApiState> ensure(res: T): T {
        if (res.state) return res
        if (res.errNo == 990001) {
            repo.sessionState.value = SessionState.EXPIRED
            throw SessionExpiredException()
        }
        throw ApiException("接口错误(errNo=${res.errNo}): ${res.error ?: "未知"}", res.errNo)
    }

    private suspend fun <T> getJson(url: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val started = android.os.SystemClock.elapsedRealtime()
            var code = 0
            var fail: String? = null
            val result: T = try {
                client.newCall(request).execute().use { resp ->
                    code = resp.code
                    val text = resp.body?.string()
                        ?: throw ApiException("空响应", resp.code)
                    if (!resp.isSuccessful) {
                        throw ApiException("HTTP ${resp.code}", resp.code, text.take(200))
                    }
                    try {
                        json.decodeFromString(serializer, text)
                    } catch (e: Exception) {
                        // 200 但不是预期 JSON：多半被 WAF 挑战页拦截，带上响应开头便于诊断
                        throw ApiException(
                            "响应不是有效JSON（可能被安全挑战拦截）",
                            -1,
                            text.take(160).replace('\n', ' '),
                        )
                    }
                }
            } catch (e: ApiException) {
                fail = "FAIL ${e.message}"
                throw e
            } catch (e: Exception) {
                fail = "EXC ${e.javaClass.simpleName}: ${e.message}"
                throw e
            }
            ApiLog.recordRequest(
                "API", url, code, android.os.SystemClock.elapsedRealtime() - started,
                fail ?: "ok",
            )
            result
        }
}
