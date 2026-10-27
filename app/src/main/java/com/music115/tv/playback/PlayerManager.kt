package com.music115.tv.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music115.tv.ServiceLocator
import com.music115.tv.api.ApiLog
import com.music115.tv.auth.SessionState
import com.music115.tv.data.FileInfo
import com.music115.tv.data.LrcLine
import com.music115.tv.data.LrcParser
import com.music115.tv.data.Lyrics
import com.music115.tv.data.PlaybackPrefs
import com.music115.tv.data.QualityGrouper
import com.music115.tv.data.SavedQueue
import com.music115.tv.data.SavedTrackRef
import com.music115.tv.data.TrackGroup
import com.music115.tv.data.TrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/**
 * UI 侧播放管理器：驱动 MediaController，维护队列元数据（pickcode → TrackGroup）、
 * 记住播放位置、无损解码失败自动降级 MP3。
 */
class PlayerManager(
    private val locator: ServiceLocator,
    private val scope: CoroutineScope,
) {
    var controller: MediaController? = null
        private set

    private val groupsById = HashMap<String, TrackGroup>()
    private val bannedExts = HashSet<String>()
    private var consecutiveErrors = 0

    var queueTitle: String = ""
        private set
    var hasQueue: Boolean = false
        private set

    var onUiChanged: (() -> Unit)? = null
    var onFallback: ((String) -> Unit)? = null

    private companion object {
        /** 当前曲之后始终保持的备播数量（大厂播放器的滚动 lookahead 缓冲） */
        const val LOOKAHEAD = 2
    }

    /** 歌词缓存：pickcode → 解析结果 */
    private val lrcCache = object : LinkedHashMap<String, Lyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>) = size > 64
    }

    /**
     * 获取歌词：内嵌歌词（文件头 USLT/Vorbis 注释）优先，其次同名 .lrc 文件
     * （组内直挂的，或回退到文件所在目录的列表里找，搜索/分类入队时用）。
     */
    suspend fun lrcFor(group: TrackGroup?): Lyrics? {
        if (group == null) return null
        // 1. 内嵌歌词
        EmbeddedLyrics.extract(locator, group.chosen.pickcode)?.let { return Lyrics.fromText(it) }
        // 2. .lrc 文件：组内直挂或父目录里找
        val direct = group.lrcPickcode
        if (direct != null) return lrcTextFor(direct)
        val parentCid = group.chosen.parentCid
        if (parentCid.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val listing = locator.listingRepository.folder(parentCid, 0)
                val base = QualityGrouper.parse(group.chosen.name, group.chosen.ext).first.lowercase()
                val lrcPc = listing.items.firstNotNullOfOrNull { item ->
                    (item as? TrackItem)?.takeIf {
                        it.file.ext == "lrc" &&
                            QualityGrouper.parse(it.file.name, "lrc").first.lowercase() == base
                    }?.file?.pickcode
                } ?: return@withContext null
                lrcTextFor(lrcPc)
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun lrcTextFor(pickcode: String): Lyrics? {
        synchronized(lrcCache) { lrcCache[pickcode]?.let { return it } }
        return withContext(Dispatchers.IO) {
            try {
                val url = locator.api.downloadUrl(pickcode).fileUrl
                    ?: return@withContext null
                val text = locator.okHttpClient.newCall(Request.Builder().url(url).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    } ?: return@withContext null
                val parsed = LrcParser.parse(text)
                if (parsed.isEmpty()) null else {
                    val result = Lyrics(syncedLines = parsed)
                    synchronized(lrcCache) { lrcCache[pickcode] = result }
                    result
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun attach(c: MediaController) {
        controller = c
        hasQueue = c.mediaItemCount > 0
        c.addListener(playerListener)
        scope.launch {
            while (true) {
                delay(10_000)
                if (controller !== c) break // 已 detach，心跳随之结束
                try {
                    withContext(Dispatchers.Main) {
                        if (c.isPlaying) persistNow()
                        maintainQueue() // 播放中的周期性对账：补队列失败后每 10s 有一次重试机会
                    }
                } catch (_: IllegalStateException) {
                    break // 控制器已释放
                }
            }
        }
        notifyUi()
    }

    fun detach() {
        controller?.removeListener(playerListener)
        controller = null
    }

    // ---- 队列构建 ----

    fun playGroups(groups: List<TrackGroup>, title: String, startIndex: Int) {
        val c = controller ?: return
        groupsById.clear()
        groups.forEach { g -> groupsById[g.chosen.pickcode] = g }
        queueTitle = title
        bannedExts.clear()
        queueExtender = null
        c.setMediaItems(groups.map { TrackMetadata.mediaItem(it) }, startIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)), 0)
        c.prepare()
        c.play()
        hasQueue = true
        persistNow()
        notifyUi()
    }

    /**
     * 队列扩展器：连播到达队尾时向其索取下一首（歌名文件夹）追加。
     * 由列表页设置；playGroups 会清空，需在之后重新赋值。
     */
    var queueExtender: (suspend () -> TrackGroup?)? = null

    /**
     * 扩展互斥锁：并发调用（对账循环/遥控下一曲连按）在此排队而不是被丢弃，
     * 每次按键最终都会前进一首。AtomicBoolean 的「忙即丢弃」策略曾导致连按下一曲被静默吞掉。
     */
    private val extendMutex = Mutex()

    private suspend fun extendOnce(): TrackGroup? {
        val ext = queueExtender ?: return null
        return extendMutex.withLock {
            // 拿到锁后重读：排队期间扩展器可能已被替换（用户中途重新点歌）
            if (queueExtender !== ext) return@withLock null
            // ext 失败只留痕，绝不冒泡
            val result = runCatching { ext() }
            result.exceptionOrNull()?.let {
                ApiLog.record("连播扩展失败 ${it.javaClass.simpleName}: ${it.message}")
            }
            val g = result.getOrNull() ?: return@withLock null
            if (queueExtender !== ext) return@withLock null
            val c = controller ?: return@withLock null
            withContext(Dispatchers.Main) {
                groupsById[g.chosen.pickcode] = g
                c.addMediaItem(c.mediaItemCount, TrackMetadata.mediaItem(g))
                preloadNextUrl()
            }
            g
        }
    }

    /**
     * 队列对账（补队列的唯一入口，所有播放事件都调它）：保证当前曲之后
     * 始终有 LOOKAHEAD 首备播。不依赖单次补歌成功——本次补不足，下一个
     * 播放事件/心跳会再试；若歌已播完停在队尾（扩展没跟上歌尾），补上后立即自动续播。
     * 对账全程跑在主线程（MediaController 只允许主线程访问）；ext() 内部是挂起网络调用，不会阻塞。
     */
    private fun maintainQueue() {
        if (queueExtender == null) return
        scope.launch(Dispatchers.Main) {
            while (true) {
                val cc = controller ?: return@launch
                if (queueExtender == null) return@launch
                if (cc.mediaItemCount - 1 - cc.currentMediaItemIndex >= LOOKAHEAD) break
                if (extendOnce() == null) break // 源头耗尽，或本次失败（后续事件重试）
            }
            val cc = controller ?: return@launch
            if (cc.playbackState == Player.STATE_ENDED && cc.hasNextMediaItem()) {
                ApiLog.record("扩展未跟上歌尾，自动续播下一首")
                cc.seekToNextMediaItem()
                cc.play()
            }
        }
    }

    /** 预解析下一曲的 CDN 直链（DownloadUrlResolver 带缓存+去重锁），消除切歌间隙。
     *  纯优化：队列并发切换时索引可能瞬间失效，任何异常一律吞掉，绝不允许影响播放。 */
    private fun preloadNextUrl() {
        val c = controller ?: return
        runCatching {
            if (c.mediaItemCount == 0) return
            val nextIndex = c.nextMediaItemIndex
            // 顺序模式播到队尾时 nextMediaItemIndex 返回 INDEX_UNSET(-1)，取值会越界
            if (nextIndex == androidx.media3.common.C.INDEX_UNSET || nextIndex == c.currentMediaItemIndex) return
            val pickcode = c.getMediaItemAt(nextIndex).mediaId
            if (pickcode.isEmpty()) return
            scope.launch {
                runCatching { locator.downloadUrlResolver.resolve(pickcode) }
            }
        }
    }

    // ---- 遥控 ----

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
        } else {
            // 队尾且还有扩展来源：排队取下一首再前进（连按会在锁上排队，逐个生效）
            scope.launch {
                if (extendOnce() != null) {
                    withContext(Dispatchers.Main) {
                        controller?.seekToNextMediaItem()
                        controller?.play()
                    }
                } else {
                    // 真的没歌了（扩展源耗尽或队列无扩展器）——给出反馈而不是静默
                    withContext(Dispatchers.Main) {
                        onFallback?.invoke("没有更多歌曲了")
                    }
                }
            }
        }
    }

    fun previous() = controller?.seekToPreviousMediaItem()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, (c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)))
    }

    /** 模式按钮循环：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序 */
    fun cycleRepeatMode(): String {
        val c = controller ?: return "顺序播放"
        val newMode: Int
        val newShuffle: Boolean
        val label: String
        if (c.shuffleModeEnabled) {
            newMode = Player.REPEAT_MODE_OFF; newShuffle = false; label = "顺序播放"
        } else when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> { newMode = Player.REPEAT_MODE_ALL; newShuffle = false; label = "列表循环" }
            Player.REPEAT_MODE_ALL -> { newMode = Player.REPEAT_MODE_ONE; newShuffle = false; label = "单曲循环" }
            else -> { newMode = Player.REPEAT_MODE_ALL; newShuffle = true; label = "随机播放" }
        }
        // 先关随机再设 repeat，避免 Media3 在 shuffle 切换时打乱/还原顺序引起跳曲
        if (!newShuffle) c.shuffleModeEnabled = false
        c.repeatMode = newMode
        if (newShuffle) c.shuffleModeEnabled = true
        return label
    }

    /** 当前播放模式描述（与 cycleRepeatMode 的文案一致） */
    fun describePlayMode(): String {
        val c = controller ?: return "顺序播放"
        return when {
            c.shuffleModeEnabled -> "随机播放"
            c.repeatMode == Player.REPEAT_MODE_ALL -> "列表循环"
            c.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
            else -> "顺序播放"
        }
    }

    fun toggleShuffle(): Boolean {
        val c = controller ?: return false
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        return c.shuffleModeEnabled
    }

    fun isPlaying(): Boolean = controller?.isPlaying == true

    fun currentGroup(): TrackGroup? =
        controller?.currentMediaItem?.mediaId?.let { groupsById[it] }

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    /** 当前曲版本标签（音质按钮文字）："臻品母带FLAC" / "MP3"；无队列返回 "音质" */
    fun currentVersionLabel(): String {
        val group = currentGroup() ?: return "音质"
        return QualityGrouper.versionLabel(group.chosen)
    }

    /** 切换到当前曲的下一个音质版本（组内循环），保持播放位置；返回新版本标签，无可切换版本返回 null */
    fun cycleQuality(): String? {
        val c = controller ?: return null
        val id = c.currentMediaItem?.mediaId ?: return null
        val group = groupsById[id] ?: return null
        if (group.versions.size <= 1) return null
        val cur = group.versions.indexOfFirst { it.pickcode == id }.coerceAtLeast(0)
        val next = group.versions[(cur + 1) % group.versions.size]
        val index = c.currentMediaItemIndex
        val position = c.currentPosition
        val wasPlaying = c.isPlaying
        val newGroup = TrackGroup(group.displayName, next, group.versions, group.lrcPickcode)
        groupsById[next.pickcode] = newGroup
        c.replaceMediaItem(index, TrackMetadata.mediaItem(newGroup))
        c.prepare()
        c.seekTo(index, position)
        if (wasPlaying) c.play()
        return QualityGrouper.versionLabel(next)
    }

    // ---- 继续播放 ----

    suspend fun savedQueueTitle(): String? =
        locator.playbackPrefs.savedQueue()?.let { q -> q.tracks.getOrNull(q.index)?.display ?: q.title }

    suspend fun restoreSavedQueue(): Boolean {
        val saved = locator.playbackPrefs.savedQueue() ?: return false
        if (saved.tracks.isEmpty()) return false
        val groups = saved.tracks.map { ref ->
            val file = FileInfo(
                fid = "", name = "${ref.display}.${ref.ext}", ext = ref.ext,
                sizeBytes = 0, pickcode = ref.pc, durationSec = 0, parentCid = "",
            )
            TrackGroup(ref.display, file, listOf(file))
        }
        groupsById.clear()
        groups.forEach { g -> groupsById[g.chosen.pickcode] = g }
        queueTitle = saved.title
        val c = controller ?: return false
        c.setMediaItems(
            groups.map { TrackMetadata.mediaItem(it) },
            saved.index.coerceIn(0, groups.size - 1),
            saved.positionMs.coerceAtLeast(0),
        )
        c.prepare()
        c.playWhenReady = false
        hasQueue = true
        notifyUi()
        return true
    }

    // ---- 内部 ----

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            consecutiveErrors = 0
            maintainQueue()
            persistNow()
            notifyUi()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveErrors = 0
                maintainQueue()
            }
            if (!isPlaying) persistNow()
            notifyUi()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            notifyUi()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 排查「有歌词无进度」类问题用：日志页能看到卡在了哪个状态
            val label = when (playbackState) {
                Player.STATE_IDLE -> "IDLE(空闲)"
                Player.STATE_BUFFERING -> "缓冲中"
                Player.STATE_READY -> "READY(可播放)"
                Player.STATE_ENDED -> "ENDED(播完)"
                else -> playbackState.toString()
            }
            ApiLog.record("播放状态 → $label")
            // 播完(ENDED)时对账是自愈兜底：扩展没跟上歌尾也能续播
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                maintainQueue()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private fun handleError(error: PlaybackException) {
        val c = controller ?: return
        ApiLog.record("播放出错 ${error.errorCode}(${error.errorCodeName}): ${error.message} / ${error.cause?.javaClass?.simpleName}: ${error.cause?.message}")
        val pickcode = c.currentMediaItem?.mediaId ?: return
        val group = groupsById[pickcode]

        // 5000–5999 为 PlaybackException 文档定义的解码/初始化/格式不支持类错误
        val decodingFailure = error.errorCode in 5000..5999
        if (group != null && decodingFailure && group.chosen.ext != "mp3") {
            bannedExts.add(group.chosen.ext)
            val alt = group.versions.firstOrNull { it.ext !in bannedExts }
            if (alt != null) {
                val index = c.currentMediaItemIndex
                val newGroup = TrackGroup(group.displayName, alt, group.versions, group.lrcPickcode)
                groupsById[alt.pickcode] = newGroup
                c.replaceMediaItem(index, TrackMetadata.mediaItem(newGroup))
                c.prepare()
                c.play()
                onFallback?.invoke("设备不支持${group.chosen.ext.uppercase()}，已切换 MP3")
                return
            }
        }

        // 直链可能已过期/失效：丢弃缓存，重试时会重新解析
        locator.downloadUrlResolver.invalidate(pickcode)
        consecutiveErrors++

        // 网络/HTTP 类错误（2000–2999，含 CDN 403）：可能整条会话都失效了。
        // 连续 3 次不再傻跳队列 —— 暂停播放并走与 990001 相同的恢复链（WafRefresher/重登）。
        if (error.errorCode in 2000..2999 && consecutiveErrors >= 3) {
            consecutiveErrors = 0
            c.pause()
            onFallback?.invoke("连续播放失败，已暂停并尝试刷新登录状态")
            locator.cookieRepository.sessionState.value = SessionState.EXPIRED
            return
        }

        // 非解码类错误先原地重试当前曲（换新直链，从出错位置续播）：
        // 偶发的链接过期/网络抖动不应导致播放停止（单曲循环尤其如此）
        if (!decodingFailure && consecutiveErrors <= 2) {
            ApiLog.record("重试当前曲（第 $consecutiveErrors 次）")
            val index = c.currentMediaItemIndex
            val position = c.currentPosition.coerceAtLeast(0)
            c.prepare()
            c.seekTo(index, position)
            c.play()
            return
        }

        // 兜底：跳下一曲，避免整队卡死（队尾时交给 next() 向扩展器取歌）
        c.prepare()
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        } else {
            next()
        }
    }

    private fun persistNow() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            hasQueue = false
            scope.launch { locator.playbackPrefs.clearQueue() }
            return
        }
        val refs = (0 until c.mediaItemCount).mapNotNull { i ->
            val item = c.getMediaItemAt(i)
            val g = groupsById[item.mediaId] ?: return@mapNotNull null
            val (title, artist) = TrackMetadata.splitName(g.displayName)
            SavedTrackRef(item.mediaId, title, artist, g.chosen.ext, g.displayName)
        }
        if (refs.isEmpty()) return
        hasQueue = true
        val queue = SavedQueue(
            title = queueTitle,
            tracks = refs,
            index = c.currentMediaItemIndex.coerceIn(0, refs.size - 1),
            positionMs = c.currentPosition.coerceAtLeast(0),
        )
        scope.launch { locator.playbackPrefs.saveQueue(queue) }
    }

    private fun notifyUi() {
        onUiChanged?.invoke()
    }
}
