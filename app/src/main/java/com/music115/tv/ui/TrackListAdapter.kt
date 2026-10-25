package com.music115.tv.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 */
class TrackListAdapter(
    private val onFolderClick: (FolderInfo, Int) -> Unit,
    private val onGroupClick: (TrackGroup, Int) -> Unit,
    private val onNearEnd: () -> Unit,
    /** 目录行图标：文件夹浏览用文件夹，歌曲列表（歌名文件夹）用音符 */
    private val folderIconRes: Int = R.drawable.ic_folder,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_TRACK = 1
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
        if (position >= items.size - 5) onNearEnd()
    }

    class FolderVH(view: View, iconRes: Int) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<android.widget.ImageView>(R.id.folder_icon)
        private val title = view.findViewById<TextView>(R.id.title)

        init {
            icon.setImageResource(iconRes)
            view.bindFocusScale()
        }

        fun bind(folder: FolderInfo, onClick: () -> Unit) {
            title.text = folder.name
            itemView.setOnClickListener { onClick() }
        }
    }

    class TrackVH(view: View) : RecyclerView.ViewHolder(view) {
        private val badge = view.findViewById<TextView>(R.id.badge)
        private val title = view.findViewById<TextView>(R.id.title)
        private val subTitle = view.findViewById<TextView>(R.id.sub_title)
        private val duration = view.findViewById<TextView>(R.id.duration)

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

        private fun formatDuration(sec: Int): String =
            "%d:%02d".format(sec / 60, sec % 60)
    }
}
