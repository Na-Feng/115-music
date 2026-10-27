package com.music115.tv.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.music115.tv.R
import com.music115.tv.data.FileInfo
import com.music115.tv.data.FolderInfo
import com.music115.tv.data.FolderItem
import com.music115.tv.data.GroupItem
import com.music115.tv.data.ListItem
import com.music115.tv.data.TrackGroup
import com.music115.tv.playback.TrackMetadata

/**
 * 列表适配器：文件夹行 + 歌曲行（同曲多版本已合并为一条）两种视图类型。
 * onNearEnd 在焦点/绑定接近末尾时触发分页（TV 上比滚动监听自然）。
 * 正在播放的歌曲（歌曲行按 pickcode、歌名文件夹行按所在目录 cid 匹配）以主题色 + 均衡器图标标出。
 */
class TrackListAdapter(
    private val onFolderClick: (FolderInfo, Int) -> Unit,
    private val onGroupClick: (TrackGroup, Int) -> Unit,
    private val onNearEnd: () -> Unit,
    /** 目录行图标：文件夹浏览用文件夹，歌曲列表（歌名文件夹）用音符 */
    private val folderIconRes: Int = R.drawable.ic_folder,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    // 正在播放：当前曲组所有版本的 pickcode + 所在目录 cid；playingActive = 是否在出声（决定是否呼吸闪烁）
    private var playingPickcodes: Set<String> = emptySet()
    private var playingFolderCid: String? = null
    private var playingActive = false

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_TRACK = 1
        private const val PAYLOAD_PLAYING = "playing"
    }

    fun submit(newItems: List<ListItem>, append: Boolean) {
        if (append) {
            val start = items.size
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        } else {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun groups(): List<TrackGroup> = items.mapNotNull { (it as? GroupItem)?.group }

    fun folders(): List<FolderInfo> = items.mapNotNull { (it as? FolderItem)?.folder }

    /** 更新正在播放的曲目；无变化时不刷新 */
    fun setPlaying(group: TrackGroup?, isPlaying: Boolean) {
        val pcs = if (group == null) emptySet()
        else (group.versions.map { it.pickcode } + group.chosen.pickcode).toSet()
        val cid = group?.chosen?.parentCid?.takeIf { it.isNotEmpty() }
        if (pcs == playingPickcodes && cid == playingFolderCid && isPlaying == playingActive) return
        playingPickcodes = pcs
        playingFolderCid = cid
        playingActive = isPlaying
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_PLAYING)
    }

    /** 正在播放的条目位置；不在列表中返回 -1 */
    fun playingPosition(): Int = items.indexOfFirst { isPlayingItem(it) }

    private fun isPlayingItem(item: ListItem): Boolean = when (item) {
        is GroupItem -> item.group.chosen.pickcode in playingPickcodes ||
            item.group.versions.any { it.pickcode in playingPickcodes }
        is FolderItem -> playingFolderCid != null && item.folder.cid == playingFolderCid
        else -> false
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is FolderItem) TYPE_FOLDER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderVH(inflater.inflate(R.layout.item_folder, parent, false), folderIconRes)
        } else {
            TrackVH(inflater.inflate(R.layout.item_track, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FolderItem -> (holder as FolderVH).bind(item.folder) {
                onFolderClick(item.folder, position)
            }
            is GroupItem -> (holder as TrackVH).bind(item.group) {
                onGroupClick(item.group, position)
            }
            else -> Unit
        }
        applyPlaying(holder, position)
        if (position >= items.size - 5) onNearEnd()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PLAYING }) {
            applyPlaying(holder, position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    // 呼吸动画随视图上/下屏启停，避免离屏的无限动画泄漏
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION && pos < items.size) applyPlaying(holder, pos)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is FolderVH -> holder.stopPulse()
            is TrackVH -> holder.stopPulse()
        }
    }

    private fun applyPlaying(holder: RecyclerView.ViewHolder, position: Int) {
        val playing = isPlayingItem(items[position])
        when (holder) {
            is FolderVH -> holder.setPlaying(playing, playing && playingActive)
            is TrackVH -> holder.setPlaying(playing, playing && playingActive)
        }
    }

    /** 无限呼吸闪烁（透明度往复），用于正在播放的图标 */
    private class Pulse(private val target: View) {
        private var anim: ObjectAnimator? = null

        fun set(on: Boolean) {
            if (on) {
                if (anim?.isRunning == true) return
                anim = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.3f).apply {
                    duration = 650
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else {
                anim?.cancel()
                anim = null
                target.alpha = 1f
            }
        }
    }

    class FolderVH(view: View, private val iconRes: Int) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.folder_icon)
        private val title = view.findViewById<TextView>(R.id.title)
        private val pulse = Pulse(icon)

        init {
            icon.setImageResource(iconRes)
            view.bindFocusScale()
        }

        fun bind(folder: FolderInfo, onClick: () -> Unit) {
            title.text = folder.name
            itemView.setOnClickListener { onClick() }
        }

        fun setPlaying(playing: Boolean, animate: Boolean) {
            icon.setImageResource(if (playing) R.drawable.ic_playing else iconRes)
            title.setTextColor(
                ContextCompat.getColor(itemView.context, if (playing) R.color.accent else R.color.text_primary)
            )
            pulse.set(animate)
        }

        fun stopPulse() = pulse.set(false)
    }

    class TrackVH(view: View) : RecyclerView.ViewHolder(view) {
        private val playingIcon = view.findViewById<ImageView>(R.id.playing_icon)
        private val badge = view.findViewById<TextView>(R.id.badge)
        private val title = view.findViewById<TextView>(R.id.title)
        private val subTitle = view.findViewById<TextView>(R.id.sub_title)
        private val duration = view.findViewById<TextView>(R.id.duration)
        private val pulse = Pulse(playingIcon)

        init {
            view.bindFocusScale()
        }

        fun bind(group: TrackGroup, onClick: () -> Unit) {
            val file = group.chosen
            badge.text = file.ext.uppercase()
            badge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    itemView.context,
                    when (file.ext) {
                        "flac" -> R.color.badge_flac
                        "mp3" -> R.color.badge_mp3
                        "wav" -> R.color.badge_wav
                        else -> R.color.badge_other
                    },
                )
            )
            val (song, artist) = TrackMetadata.splitName(group.displayName)
            title.text = song
            val parts = mutableListOf<String>()
            if (artist.isNotEmpty()) parts.add(artist)
            if (group.versions.size > 1) parts.add("${group.versions.size}个版本")
            subTitle.text = parts.joinToString(" · ")
            subTitle.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            duration.text = if (file.durationSec > 0) formatDuration(file.durationSec) else ""
            itemView.setOnClickListener { onClick() }
        }

        fun setPlaying(playing: Boolean, animate: Boolean) {
            playingIcon.visibility = if (playing) View.VISIBLE else View.GONE
            title.setTextColor(
                ContextCompat.getColor(itemView.context, if (playing) R.color.accent else R.color.text_primary)
            )
            pulse.set(animate)
        }

        fun stopPulse() = pulse.set(false)

        private fun formatDuration(sec: Int): String =
            "%d:%02d".format(sec / 60, sec % 60)
    }
}
