package com.music115.tv.data

import com.music115.tv.api.Api115
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 列表仓库：目录列表带内存 LRU 缓存（减少对 115 的请求，防风控）；
 * 请求经单线程串行化，绝不并发轰炸接口。
 */
class ListingRepository(private val api: Api115, private val prefs: PlaybackPrefs) {

    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Listing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>): Boolean =
            size > 40
    }

    suspend fun folder(cid: String, offset: Int, refresh: Boolean = false): Listing =
        cached("f:$cid:$offset", refresh) { api.files(cid, offset).toListing(cid) }

    /** 曲库根：用户在引导页设定的文件夹优先；未设定时回退到根目录下名为「音乐」的文件夹 */
    @Volatile
    private var musicFolderCache: FolderInfo? = null

    suspend fun musicFolder(refresh: Boolean = false): FolderInfo? {
        if (!refresh) musicFolderCache?.let { return it }
        prefs.musicFolder()?.let {
            musicFolderCache = it
            return it
        }
        val root = folder("0", 0, refresh)
        val found = root.items.firstNotNullOfOrNull { item ->
            (item as? FolderItem)?.folder?.takeIf { it.name == "音乐" }
        }
        if (found != null) musicFolderCache = found
        return found
    }

    private suspend fun hasUserMusicFolder(): Boolean = prefs.musicFolder() != null

    fun onMusicFolderChanged() {
        musicFolderCache = null
    }

    suspend fun category(offset: Int, refresh: Boolean = false): Listing =
        // 按歌名升序：同曲多版本天然相邻，跨页也不会被拆散成两行
        cached("c:0:$offset", refresh) {
            api.files("0", offset, type = 3, orderBy = "file_name", ascending = true).toListing("0")
        }

    suspend fun search(keyword: String, offset: Int): Listing =
        withContext(serialDispatcher) {
            api.files("0", offset, type = 3, searchValue = keyword).toListing("0")
        }

    fun invalidate() {
        cache.clear()
    }

    private suspend fun cached(key: String, refresh: Boolean, load: suspend () -> Listing): Listing =
        withContext(serialDispatcher) {
            mutex.withLock {
                if (!refresh) cache[key]?.let { return@withLock it }
                val listing = load()
                cache[key] = listing
                listing
            }
        }
}
