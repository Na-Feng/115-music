package com.music115.tv.playback

import com.music115.tv.api.Api115
import com.music115.tv.api.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * pickcode → CDN 直链 的内存缓存。复用直到 403 失效（不解析 t= 时效，
 * 由 One15DataSource 的 403 驱动失效重取，简单可靠）。
 */
class DownloadUrlResolver(private val api: Api115) {

    private val cache = ConcurrentHashMap<String, String>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(pickcode: String): String {
        cache[pickcode]?.let { return it }
        val mutex = mutexes.computeIfAbsent(pickcode) { Mutex() }
        return mutex.withLock {
            cache[pickcode]?.let { return it }
            val resp = api.downloadUrl(pickcode)
            val url = resp.fileUrl ?: throw ApiException("未取得播放直链", -1)
            cache[pickcode] = url
            url
        }
    }

    fun invalidate(pickcode: String) {
        cache.remove(pickcode)
    }
}
