package com.music115.tv.ui

import android.widget.TextView
import com.music115.tv.R
import com.music115.tv.data.FolderInfo
import com.music115.tv.data.FolderItem
import com.music115.tv.data.Listing
import com.music115.tv.data.QualityGrouper
import com.music115.tv.data.TrackGroup
import com.music115.tv.data.TrackItem
import java.util.ArrayDeque

/** 文件夹浏览：内部维护目录栈，BACK 逐级返回；首次自动进入「音乐」目录 */
class FolderFragment : ListBaseFragment() {

    private val stack = ArrayDeque<String>()
    private var breadcrumbView: TextView? = null
    private var currentFolderName = "文件夹"
    private var autoEnteredMusic = false

    init {
        stack.addLast("0")
    }

    override val layoutId: Int = R.layout.fragment_list

    override suspend fun fetchPage(offset: Int): Listing =
        locator.listingRepository.folder(stack.last(), offset)

    override fun onListingLoaded(listing: Listing) {
        breadcrumbView?.text = listing.breadcrumb.joinToString(" / ") { it.name }
        listing.breadcrumb.lastOrNull()?.let { currentFolderName = it.name }
        // 曲库根是「音乐」目录，其余根目录（最近接收/云下载/相册等）与本应用无关
        if (!autoEnteredMusic && stack.size == 1) {
            autoEnteredMusic = true
            listing.items.firstNotNullOfOrNull { item ->
                (item as? FolderItem)?.folder?.takeIf { it.name == "音乐" }
            }?.let { music ->
                stack.addLast(music.cid)
                // loadInitial 仍在执行中（loading=true），延迟到本轮结束后再进
                view?.post { loadInitial(refresh = true) }
            }
        }
    }

    override fun onFolderClicked(folder: FolderInfo) {
        stack.addLast(folder.cid)
        loadInitial()
    }

    override fun onBaseViewCreated(view: android.view.View) {
        breadcrumbView = view.findViewById(R.id.breadcrumb)
    }

    /** @return true 表示已返回上一级（事件被消费） */
    fun onBackPressed(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        loadInitial()
        return true
    }

    override fun queueTitle(): String = currentFolderName

    /**
     * 歌曲文件夹内点歌的续播：本曲播完接父目录里排在后面的同级歌曲文件夹，
     * 沿父目录顺序翻页向后找（和「全部音乐」的整库连播同一体验）。
     */
    override fun nextGroupProvider(): (suspend () -> TrackGroup?)? {
        if (stack.size <= 1) return null
        val parentCid = stack.toList()[stack.size - 2]
        val currentCid = stack.last()
        return ext@{
            var offset = 0
            var seenCurrent = false
            for (i in 0 until 200) {
                val listing = runCatching { locator.listingRepository.folder(parentCid, offset) }
                    .getOrNull() ?: return@ext null
                for (item in listing.items) {
                    val folder = (item as? FolderItem)?.folder ?: continue
                    if (!seenCurrent) {
                        if (folder.cid == currentCid) seenCurrent = true
                    } else {
                        loadFolderGroups(folder).firstOrNull()?.let { return@ext it }
                    }
                }
                offset += listing.items.size
                if (listing.items.isEmpty() || offset >= listing.total) return@ext null
            }
            null
        }
    }

    private suspend fun loadFolderGroups(folder: FolderInfo): List<TrackGroup> {
        val listing = locator.listingRepository.folder(folder.cid, 0)
        val files = listing.items.mapNotNull { (it as? TrackItem)?.file }
        return QualityGrouper.groupAll(files)
    }
}
