package com.music115.tv.playback

import com.music115.tv.api.Api115
import com.music115.tv.api.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * pickcode → CDN 直链 的内存缓存。缓存只保留 [TTL_MS]（够预加载下一曲用），
 * 过期后重新解析，避免单曲循环/长时间播放时反复复用已失效的旧直链；
 * 期间若遇 403 等仍由 One15DataSource 驱动失效重取。
 */
class DownloadUrlResolver(private val api: Api115) {

    private class Entry(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun fresh(pickcode: String): String? {
        val e = cache[pickcode] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            cache.remove(pickcode, e)
            return null
        }
        return e.url
    }

    suspend fun resolve(pickcode: String): String {
        fresh(pickcode)?.let { return it }
        val mutex = mutexes.computeIfAbsent(pickcode) { Mutex() }
        return mutex.withLock {
            fresh(pickcode)?.let { return it }
            val resp = api.downloadUrl(pickcode)
            val url = resp.fileUrl ?: throw ApiException("未取得播放直链", -1)
            cache[pickcode] = Entry(url, System.currentTimeMillis())
            url
        }
    }

    fun invalidate(pickcode: String) {
        cache.remove(pickcode)
    }

    private companion object {
        const val TTL_MS = 10 * 60_000L
    }
}
