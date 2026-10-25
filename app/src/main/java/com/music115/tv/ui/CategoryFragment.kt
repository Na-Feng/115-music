package com.music115.tv.ui

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.music115.tv.R
import com.music115.tv.data.FolderInfo
import com.music115.tv.data.FolderItem
import com.music115.tv.data.ListItem
import com.music115.tv.data.Listing
import com.music115.tv.data.QualityGrouper
import com.music115.tv.data.TrackGroup
import com.music115.tv.data.TrackItem
import kotlinx.coroutines.launch

/**
 * 歌曲列表（侧栏「全部音乐」）：直接列出曲库根「音乐」文件夹下的歌曲文件夹——
 * 一行一首歌（文件夹名即歌名），天然无重复、无杂散音频。
 * 点击某首歌时才拉取其文件夹内的多品质文件入队（默认最高音质），其余歌曲
 * 播到队尾时由 queueExtender 逐首追加，实现整库连播且请求频率等同人工浏览。
 */
class CategoryFragment : ListBaseFragment() {

    override val layoutId: Int = R.layout.fragment_list
    override val pageTag: String get() = "歌曲列表"

    /** 一行是一首歌（歌名文件夹），用音符而非文件夹图标 */
    override val rowIconRes: Int get() = com.music115.tv.R.drawable.ic_note

    /** 连播游标：正在播的歌曲在歌曲列表中的下一位 */
    private var extendPos = -1

    override suspend fun fetchPage(offset: Int): Listing {
        val music = locator.listingRepository.musicFolder()
            ?: throw IllegalStateException("根目录下未找到「音乐」文件夹")
        return locator.listingRepository.folder(music.cid, offset)
    }

    override fun toDisplay(listing: Listing): List<ListItem> =
        listing.items.filterIsInstance<FolderItem>()

    override fun queueTitle(): String = "全部音乐"

    override fun onFolderClicked(folder: FolderInfo) {
        val manager = locator.playerManager
        if (manager.controller == null) {
            Toast.makeText(requireContext(), "播放器初始化中，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val folders = adapter.folders()
        extendPos = folders.indexOfFirst { it.cid == folder.cid }
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = loadSongGroups(folder)
            if (groups.isEmpty()) {
                Toast.makeText(requireContext(), "该文件夹内没有可播放的音频文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            manager.playGroups(groups, queueTitle(), 0)
            // 播到队尾时自动取下一首歌追加；列表页未加载完则边播边补页
            val extender: suspend () -> TrackGroup? = ext@{
                for (i in 0 until 1000) {
                    val next = adapter.folders().getOrNull(extendPos + 1)
                    if (next != null) {
                        extendPos++
                        loadSongGroups(next).firstOrNull()?.let { return@ext it }
                    } else if (exhausted || !ensureMoreItems()) {
                        return@ext null
                    }
                }
                null
            }
            manager.queueExtender = extender
            (activity as? MainHost)?.openPlayerScreen()
        }
    }

    /** 拉取一个歌曲文件夹并按音质分组（目录缓存命中则零请求） */
    private suspend fun loadSongGroups(folder: FolderInfo): List<TrackGroup> {
        val listing = locator.listingRepository.folder(folder.cid, 0)
        val files = listing.items.mapNotNull { (it as? TrackItem)?.file }
        return QualityGrouper.groupAll(files)
    }
}
