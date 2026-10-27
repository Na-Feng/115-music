package com.music115.tv.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.music115.tv.App
import com.music115.tv.R
import com.music115.tv.ServiceLocator
import com.music115.tv.api.ApiLog
import com.music115.tv.api.SessionExpiredException
import com.music115.tv.data.FileInfo
import com.music115.tv.data.FolderInfo
import com.music115.tv.data.FolderItem
import com.music115.tv.data.GroupItem
import com.music115.tv.data.ListItem
import com.music115.tv.data.Listing
import com.music115.tv.data.QualityGrouper
import com.music115.tv.data.TrackGroup
import com.music115.tv.data.TrackItem

/**
 * 列表页基类：RecyclerView + 分页 + 错误态 + 点击建队播放。
 * fragment_list / fragment_search 共用同一组控件 id。
 */
abstract class ListBaseFragment : Fragment() {

    protected val locator: ServiceLocator by lazy {
        (requireActivity().application as App).locator
    }

    private var loading = false
    protected var exhausted = false
        private set
    private var offset = 0

    protected lateinit var adapter: TrackListAdapter
        private set

    protected abstract val layoutId: Int
    protected abstract suspend fun fetchPage(offset: Int): Listing
    protected abstract fun queueTitle(): String
    /** 诊断日志里的页面标识 */
    protected open val pageTag: String get() = javaClass.simpleName
    protected open fun onListingLoaded(listing: Listing) {}
    protected open fun onFolderClicked(folder: FolderInfo) {}
    protected open fun onBaseViewCreated(view: View) {}

    /** 播放队列耗尽时的续播来源（返回下一组）；默认无——队列播完即止 */
    protected open fun nextGroupProvider(): (suspend () -> TrackGroup?)? = null

    /** 目录行图标：文件夹浏览用文件夹，歌曲列表（一行=一首歌）用音符 */
    protected open val rowIconRes: Int get() = R.drawable.ic_folder

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: android.os.Bundle?,
    ): View = inflater.inflate(layoutId, container, false)

    private val playerListener = {
        updatePlayingIndicator()
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        adapter = TrackListAdapter(
            onFolderClick = { folder, _ -> onFolderClicked(folder) },
            onGroupClick = { group, _ -> playFrom(adapter.groups(), group) },
            onNearEnd = { loadMore() },
            folderIconRes = rowIconRes,
        )
        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ListBaseFragment.adapter
        }
        view.findViewById<Button>(R.id.retry_button).setOnClickListener { loadInitial(refresh = true) }
        onBaseViewCreated(view)
        loadInitial()
    }

    override fun onStart() {
        super.onStart()
        locator.playerManager.addUiListener(playerListener)
        updatePlayingIndicator()
    }

    override fun onStop() {
        locator.playerManager.removeUiListener(playerListener)
        super.onStop()
    }

    private fun updatePlayingIndicator() {
        val pm = locator.playerManager
        adapter.setPlaying(pm.currentGroup(), pm.isPlaying())
    }

    fun loadInitial(refresh: Boolean = false) {
        if (loading) {
            ApiLog.record("页面[$pageTag] loadInitial 跳过（上次加载仍在进行）")
            return
        }
        loading = true
        ApiLog.record("页面[$pageTag] loadInitial 开始 登录Cookie=${locator.cookieRepository.hasLoginCookies()}")
        setState(progressVisible = true, errorVisible = false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!locator.cookieRepository.hasLoginCookies()) {
                    ApiLog.record("页面[$pageTag] 未登录守卫命中，不发起请求")
                    showError("尚未登录 —— 请到「设置」页扫码登录后再试")
                    return@launch
                }
                val listing = fetchPage(0)
                ApiLog.record("页面[$pageTag] 加载成功 count=${listing.total} 条目=${listing.items.size}")
                // 同曲多版本合并为一行展示；分页 offset 仍按原始条目数计
                val display = toDisplay(listing)
                adapter.submit(display, append = false)
                offset = listing.items.size
                exhausted = offset >= listing.total
                onListingLoaded(listing)
                if (display.isEmpty()) {
                    showError("列表为空（服务端 count=${listing.total}）——可点「重试」再试")
                } else {
                    setState(recyclerVisible = true)
                    updatePlayingIndicator()
                }
            } catch (e: SessionExpiredException) {
                ApiLog.record("页面[$pageTag] 会话过期(990001)")
                showError("登录已过期（errNo 990001）—— 请到「设置」页重新扫码登录")
            } catch (e: Exception) {
                ApiLog.record("页面[$pageTag] 加载异常 ${e.javaClass.simpleName}: ${e.message}")
                showError("加载失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                loading = false
                setState(progressVisible = false)
            }
        }
    }

    private fun loadMore() {
        if (loading || exhausted) return
        viewLifecycleOwner.lifecycleScope.launch { ensureMoreItems() }
    }

    /** 拉取下一页并追加到列表；返回是否有新条目（连播扩展在歌曲耗尽时用它补页） */
    protected suspend fun ensureMoreItems(): Boolean {
        if (loading || exhausted) return false
        loading = true
        try {
            val listing = fetchPage(offset)
            val display = toDisplay(listing)
            if (display.isNotEmpty()) adapter.submit(display, append = true)
            offset += listing.items.size
            exhausted = offset >= listing.total || listing.items.isEmpty()
            return display.isNotEmpty()
        } catch (_: Exception) {
            // 静默：分页失败不打断浏览
            return false
        } finally {
            loading = false
        }
    }

    /** 目录/曲目原始条目 → 展示条目（同曲多版本合并为一行） */
    protected open fun toDisplay(listing: Listing): List<ListItem> = groupDisplay(listing)

    private fun groupDisplay(listing: Listing): List<ListItem> {
        val display = mutableListOf<ListItem>()
        val files = mutableListOf<FileInfo>()
        for (item in listing.items) {
            when (item) {
                is FolderItem -> display.add(item)
                is TrackItem -> files.add(item.file)
                else -> Unit
            }
        }
        QualityGrouper.groupAll(files).forEach { display.add(GroupItem(it)) }
        return display
    }

    private fun playFrom(groups: List<TrackGroup>, clicked: TrackGroup) {
        val manager = locator.playerManager
        if (manager.controller == null) {
            Toast.makeText(requireContext(), "播放器初始化中，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        manager.playGroups(groups, queueTitle(), groups.indexOf(clicked).coerceAtLeast(0))
        manager.queueExtender = nextGroupProvider()
        (activity as? MainHost)?.openPlayerScreen()
    }

    /** 只更新显式传入的状态位（null = 保持不变），避免 finally 收起进度时把列表/错误态一起重置 */
    private fun setState(progressVisible: Boolean? = null, recyclerVisible: Boolean? = null, errorVisible: Boolean? = null) {
        val view = view ?: return
        progressVisible?.let {
            view.findViewById<View>(R.id.progress).visibility = if (it) View.VISIBLE else View.GONE
        }
        recyclerVisible?.let {
            view.findViewById<RecyclerView>(R.id.recycler).visibility = if (it) View.VISIBLE else View.INVISIBLE
        }
        errorVisible?.let {
            view.findViewById<View>(R.id.error_view).visibility = if (it) View.VISIBLE else View.GONE
        }
    }

    private fun showError(message: String) {
        val view = view ?: return
        view.findViewById<TextView>(R.id.error_text).text = message
        setState(errorVisible = true)
        view.findViewById<Button>(R.id.retry_button).requestFocus()
    }
}
