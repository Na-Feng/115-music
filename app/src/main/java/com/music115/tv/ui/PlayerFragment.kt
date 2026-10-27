package com.music115.tv.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.music115.tv.App
import com.music115.tv.R
import com.music115.tv.data.Lyrics
import com.music115.tv.playback.TrackMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 全屏播放页：封面、歌词、进度、走带控制、循环模式。
 * 遥控交互：进页默认焦点在进度条 —— ←→ 快退/快进（按住加速，松手才真正 seek），
 * OK 播放/暂停，↓ 进入按钮行。
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private val locator by lazy { (requireActivity().application as App).locator }
    private val manager get() = locator.playerManager

    private var artwork: ImageView? = null
    private var lyricsList: RecyclerView? = null
    private val lyricsAdapter = LyricsAdapter()
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var formatBadge: TextView? = null
    private var seekbar: SeekBar? = null
    private var seekHint: TextView? = null
    private var timeElapsed: TextView? = null
    private var timeTotal: TextView? = null
    private var buttonMode: Button? = null
    private var playPause: ImageButton? = null
    private var backgroundView: ImageView? = null
    private var bgJob: Job? = null

    private var seeking = false
    /** 遥控快进/快退中的目标位置（预览用，松手时才提交 seek，避免按住时反复缓冲） */
    private var pendingSeekMs: Long? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 500)
        }
    }
    private var lastArtwork: ByteArray? = null
    private var artworkCleared = false

    // 歌词状态：按当前曲组加载（内嵌优先），行高亮随进度切换；无时间戳则静态平铺
    private var lrcPickcode: String? = null
    private var lyrics: Lyrics? = null
    private var lrcJob: Job? = null

    private val uiListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshAll()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        com.music115.tv.api.ApiLog.record("播放页视图已创建")
        backgroundView = view.findViewById(R.id.player_background)
        artwork = view.findViewById<ImageView>(R.id.artwork).apply {
            clipToOutline = true // 圆角跟随占位背景的形状
        }
        lyricsList = view.findViewById<RecyclerView>(R.id.lyrics_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lyricsAdapter
            itemAnimator = null // 高亮切换自带动画，不要默认的交叉淡入
            // 上下各留半屏空白，首行/末行也能滚到正中
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                val half = (bottom - top) / 2
                if (v.paddingTop != half) v.setPadding(0, half, 0, half)
            }
        }
        titleView = view.findViewById(R.id.track_title)
        artistView = view.findViewById(R.id.track_artist)
        formatBadge = view.findViewById(R.id.format_badge)
        seekHint = view.findViewById(R.id.seek_hint)
        seekbar = view.findViewById<SeekBar>(R.id.seekbar).apply {
            max = 1000
            val accent = ContextCompat.getColor(context, R.color.accent)
            val idle = ContextCompat.getColor(context, R.color.text_secondary)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(idle)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimes(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setOnFocusChangeListener { _, hasFocus ->
                thumbTintList = ColorStateList.valueOf(if (hasFocus) accent else idle)
                seekHint?.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (!hasFocus) commitPendingSeek()
            }
            setOnKeyListener { _, keyCode, event -> handleSeekbarKey(keyCode, event) }
        }
        timeElapsed = view.findViewById(R.id.time_elapsed)
        timeTotal = view.findViewById(R.id.time_total)
        buttonMode = view.findViewById<Button>(R.id.button_mode).apply {
            setOnClickListener { text = manager.cycleRepeatMode() }
        }
        view.findViewById<Button>(R.id.button_quality).apply {
            setOnClickListener {
                val label = manager.cycleQuality()
                text = manager.currentVersionLabel()
                if (label == null) {
                    Toast.makeText(context, "本曲没有其他音质版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
        playPause = view.findViewById<ImageButton>(R.id.button_play_pause).apply {
            setOnClickListener { manager.togglePlayPause() }
        }
        view.findViewById<ImageButton>(R.id.button_prev).setOnClickListener { manager.previous() }
        view.findViewById<ImageButton>(R.id.button_next).setOnClickListener { manager.next() }
    }

    override fun onStart() {
        super.onStart()
        manager.controller?.addListener(uiListener)
        handler.post(ticker)
        refreshAll()
    }

    override fun onStop() {
        manager.controller?.removeListener(uiListener)
        handler.removeCallbacks(ticker)
        commitPendingSeek()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 每次进入播放页焦点都回到进度条：←→ 直接快进快退，OK 暂停
        if (!hidden) seekbar?.post { seekbar?.requestFocus() }
    }

    // ---- 遥控：进度条按键 ----

    private fun handleSeekbarKey(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (dir != 0) {
            val c = manager.controller ?: return true
            val duration = c.duration
            if (duration <= 0) {
                // 时长未知（缓冲中）：退化为直接 ±10s
                if (event.action == KeyEvent.ACTION_DOWN) manager.seekBy(dir * 10_000L)
                return true
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // 单击 10s；按住后每次重复 2s，持续按住提速到 5s（约 40s/秒 → 100s/秒）
                    val step = when {
                        event.repeatCount == 0 -> 10_000L
                        event.repeatCount < 20 -> 2_000L
                        else -> 5_000L
                    }
                    val base = pendingSeekMs ?: c.currentPosition
                    val target = (base + dir * step).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
                    pendingSeekMs = target
                    seeking = true
                    val progress = (target * 1000 / duration).toInt().coerceIn(0, 1000)
                    seekbar?.progress = progress
                    updateTimes(progress)
                }
                KeyEvent.ACTION_UP -> commitPendingSeek()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { manager.togglePlayPause(); true }
            else -> false
        }
    }

    private fun commitPendingSeek() {
        pendingSeekMs?.let { manager.seekTo(it) }
        pendingSeekMs = null
        seeking = false
    }

    // ---- 刷新 ----

    private fun refreshAll() {
        val c = manager.controller ?: return
        val group = manager.currentGroup()
        val (title, artist) = group?.let { TrackMetadata.splitName(it.displayName) }
            ?: (c.mediaMetadata.title?.toString() ?: "") to (c.mediaMetadata.artist?.toString() ?: "")
        titleView?.text = title
        artistView?.text = artist

        group?.let { g ->
            formatBadge?.text = g.chosen.ext.uppercase()
            formatBadge?.visibility = View.VISIBLE
        } ?: run { formatBadge?.visibility = View.GONE }

        playPause?.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        buttonMode?.text = manager.describePlayMode()
        view?.findViewById<Button>(R.id.button_quality)?.text = manager.currentVersionLabel()

        val data = c.mediaMetadata.artworkData
        if (data != null) {
            // Media3 从音频流自动提取内嵌封面；仅在数据变化时解码
            if (data !== lastArtwork) {
                lastArtwork = data
                artworkCleared = false
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bmp ->
                    artwork?.setImageBitmap(bmp)
                    updateBlurredBackground(bmp)
                }
            }
        } else if (!artworkCleared) {
            // 无封面：露出占位图（渐变+音符），背景回到主题渐变
            lastArtwork = null
            artworkCleared = true
            bgJob?.cancel()
            artwork?.setImageDrawable(null)
            backgroundView?.setImageResource(R.drawable.bg_player_default)
        }

        updateLyrics(c, group)
        updateProgress()
    }

    /** 歌词：曲目变化时拉取，进度变化时只切换高亮行并居中滚动 */
    private fun updateLyrics(c: Player, group: com.music115.tv.data.TrackGroup?) {
        val key = group?.chosen?.pickcode
        if (key != lrcPickcode) {
            lrcPickcode = key
            lrcJob?.cancel()
            lyrics = null
            if (group == null) {
                showLyrics(false)
            } else {
                lyricsAdapter.submit(listOf("歌词加载中…"))
                showLyrics(true)
                lrcJob = viewLifecycleOwner.lifecycleScope.launch {
                    val loaded = manager.lrcFor(group)
                    if (lrcPickcode != key) return@launch // 已切歌
                    lyrics = loaded
                    when {
                        loaded == null -> showLyrics(false)
                        loaded.staticText != null -> {
                            lyricsAdapter.submit(
                                loaded.staticText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            )
                            lyricsList?.scrollToPosition(0)
                        }
                        else -> {
                            lyricsAdapter.submit(loaded.syncedLines.orEmpty().map { it.text })
                            updateLyricsHighlight(c.currentPosition, jump = true)
                        }
                    }
                }
            }
        } else if (lyrics?.syncedLines != null) {
            updateLyricsHighlight(c.currentPosition, jump = false)
        }
    }

    /** 封面 → 模糊背景（后台线程生成，切歌自动替换；纯视觉优化，任何失败只记日志不影响播放） */
    private fun updateBlurredBackground(src: android.graphics.Bitmap) {
        val view = backgroundView ?: return
        bgJob?.cancel()
        bgJob = viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val blurred = runCatching {
                val w = view.width.coerceAtLeast(1280)
                val h = view.height.coerceAtLeast(720)
                BitmapBlur.blurredBackground(src, w, h)
            }.onFailure {
                com.music115.tv.api.ApiLog.record("模糊背景生成失败(已忽略) ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull() ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                backgroundView?.setImageBitmap(blurred)
            }
        }
    }

    private fun showLyrics(show: Boolean) {
        // 左封面/右歌词同屏：封面常驻，只切换歌词面板（无歌词时封面自动居中）
        lyricsList?.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** @param jump true = 刚加载/切歌，直接定位不做平滑滚动 */
    private fun updateLyricsHighlight(positionMs: Long, jump: Boolean) {
        val lines = lyrics?.syncedLines ?: return
        var idx = lines.indexOfLast { it.timeMs <= positionMs }
        if (idx == -1) idx = 0
        if (idx == lyricsAdapter.current && !jump) return
        lyricsAdapter.setCurrent(idx)
        val list = lyricsList ?: return
        if (jump) {
            list.post {
                val lm = list.layoutManager as? LinearLayoutManager ?: return@post
                // 让当前行大致停在中间（padding 已是半高，偏移 0 即居中附近）
                lm.scrollToPositionWithOffset(idx, 0)
            }
        } else {
            LyricsAdapter.scrollToCenter(list, idx)
        }
    }

    private fun updateProgress() {
        val c = manager.controller ?: return
        val duration = c.duration
        val position = c.currentPosition.coerceAtLeast(0)
        if (!seeking && duration > 0) {
            seekbar?.setProgress(((position.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000))
        }
        updateTimes(seekbar?.progress ?: 0)
        playPause?.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        buttonMode?.text = manager.describePlayMode()
    }

    private fun updateTimes(progress: Int) {
        val c = manager.controller ?: return
        val duration = c.duration
        val ms = if (duration > 0) (duration * progress / 1000.0).toLong() else 0
        timeElapsed?.text = formatMs(ms)
        timeTotal?.text = when {
            duration > 0 -> formatMs(duration)
            c.playbackState == Player.STATE_BUFFERING -> "缓冲中…"
            else -> "--:--"
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
