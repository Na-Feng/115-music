package com.music115.tv.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.music115.tv.App
import com.music115.tv.R
import com.music115.tv.data.Lyrics
import com.music115.tv.playback.TrackMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 全屏播放页：封面、进度（遥控左右键 ±10s）、走带控制、循环模式 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private val locator by lazy { (requireActivity().application as App).locator }
    private val manager get() = locator.playerManager

    private var artwork: ImageView? = null
    private var lyricsScroll: ScrollView? = null
    private var lyricsText: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var formatBadge: TextView? = null
    private var seekbar: SeekBar? = null
    private var timeElapsed: TextView? = null
    private var timeTotal: TextView? = null
    private var buttonMode: Button? = null
    private var playPause: ImageButton? = null
    private var backgroundView: ImageView? = null
    private var bgJob: Job? = null

    private var seeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 500)
        }
    }
    private var lastArtwork: ByteArray? = null

    // 歌词状态：按当前曲组加载（内嵌优先），行高亮随进度切换；无时间戳则静态平铺
    private var lrcPickcode: String? = null
    private var lyrics: Lyrics? = null
    private var lrcIndex = -1
    private var lrcJob: Job? = null

    private val uiListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshAll()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        com.music115.tv.api.ApiLog.record("播放页视图已创建")
        backgroundView = view.findViewById(R.id.player_background)
        artwork = view.findViewById(R.id.artwork)
        lyricsScroll = view.findViewById(R.id.lyrics_scroll)
        lyricsText = view.findViewById(R.id.lyrics_text)
        titleView = view.findViewById(R.id.track_title)
        artistView = view.findViewById(R.id.track_artist)
        formatBadge = view.findViewById(R.id.format_badge)
        seekbar = view.findViewById<SeekBar>(R.id.seekbar).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTimes(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { manager.seekBy(-10_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { manager.seekBy(10_000); true }
                    KeyEvent.KEYCODE_DPAD_CENTER -> { manager.togglePlayPause(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { manager.togglePlayPause(); true }
                    else -> false
                }
            }
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
        super.onStop()
    }

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

        c.mediaMetadata.artworkData?.let { data ->
            // Media3 从音频流自动提取内嵌封面；仅在数据变化时解码
            if (data !== lastArtwork) {
                lastArtwork = data
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bmp ->
                    artwork?.setImageBitmap(bmp)
                    updateBlurredBackground(bmp)
                }
            }
        } ?: run {
            lastArtwork = null
            artwork?.setImageDrawable(null)
            backgroundView?.setImageDrawable(null)
        }

        updateLyrics(c, group)
        updateProgress()
    }

    /** 歌词：曲目变化时拉取（有 lrc 则盖住封面显示），进度变化时高亮滚动 */
    private fun updateLyrics(c: Player, group: com.music115.tv.data.TrackGroup?) {
        val pickcode = group?.lrcPickcode
        val key = group?.chosen?.pickcode
        if (key != lrcPickcode) {
            lrcPickcode = key
            lrcJob?.cancel()
            lyrics = null
            lrcIndex = -1
            if (group == null) {
                showLyrics(false)
            } else {
                lyricsText?.text = "歌词加载中…"
                showLyrics(true)
                lrcJob = viewLifecycleOwner.lifecycleScope.launch {
                    val loaded = manager.lrcFor(group)
                    if (lrcPickcode != key) return@launch // 已切歌
                    lyrics = loaded
                    lrcIndex = -1
                    when {
                        loaded == null -> showLyrics(false)
                        loaded.staticText != null -> {
                            lyricsText?.text = loaded.staticText
                            val scroll = lyricsScroll
                            scroll?.post { scroll.scrollTo(0, 0) }
                        }
                        else -> updateLyricsHighlight(c.currentPosition)
                    }
                }
            }
        } else if (lyrics?.syncedLines != null) {
            updateLyricsHighlight(c.currentPosition)
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
        lyricsScroll?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateLyricsHighlight(positionMs: Long) {
        val lines = lyrics?.syncedLines ?: return
        var idx = lines.indexOfLast { it.timeMs <= positionMs }
        if (idx == -1) idx = 0
        if (idx == lrcIndex) return
        lrcIndex = idx

        val context = context ?: return
        val accent = ContextCompat.getColor(context, R.color.accent)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line.text)
            if (i == idx) {
                sb.setSpan(ForegroundColorSpan(accent), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.2f), start, sb.length,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        lyricsText?.text = sb
        lyricsScroll?.post {
            val text = lyricsText ?: return@post
            val scroll = lyricsScroll ?: return@post
            if (text.layout == null || idx >= text.layout.lineCount) return@post
            val target = text.layout.getLineTop(idx) - scroll.height / 3
            scroll.smoothScrollTo(0, target.coerceAtLeast(0))
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
