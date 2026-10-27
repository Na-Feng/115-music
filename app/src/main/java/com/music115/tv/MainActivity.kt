package com.music115.tv

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music115.tv.auth.LoginFragment
import com.music115.tv.api.ApiLog
import com.music115.tv.auth.SessionState
import com.music115.tv.auth.WafRefresher
import com.music115.tv.playback.PlaybackService
import com.music115.tv.ui.CategoryFragment
import com.music115.tv.ui.FolderFragment
import com.music115.tv.ui.ListBaseFragment
import com.music115.tv.ui.LogFragment
import com.music115.tv.ui.MainHost
import com.music115.tv.ui.PlayerFragment
import com.music115.tv.ui.SearchFragment
import com.music115.tv.ui.SettingsFragment
import com.music115.tv.ui.bindFocusScale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MainHost {

    private val locator: ServiceLocator by lazy { (application as App).locator }
    private val manager get() = locator.playerManager

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = manager.controller

    private lateinit var sidebar: LinearLayout
    private lateinit var miniContainer: FrameLayout
    private var miniTitle: TextView? = null
    private var miniPlayPause: ImageButton? = null
    private var continueRow: View? = null
    private var continueLabel: TextView? = null

    private val tabRows = mutableListOf<Pair<View, Int>>()
    private val listFragments = arrayOfNulls<Fragment>(5)
    private var playerFragment: PlayerFragment? = null
    private var loginFragment: LoginFragment? = null
    private var folderPickerFragment: com.music115.tv.ui.FolderPickerFragment? = null
    private var selectedTab = -1
    private var sessionHandling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        com.music115.tv.api.ApiLog.record("MainActivity onCreate")

        sidebar = findViewById(R.id.sidebar)
        miniContainer = findViewById(R.id.mini_player_container)
        setupMiniBar()
        buildSidebar()
        selectTab(0)

        manager.onUiChanged = { runOnUiThread { updateMiniBar(); refreshContinueRow() } }
        manager.onFallback = { message ->
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
        observeSession()
        // 未登录（首次安装或登录过期）→ 直接引导到扫码页
        if (!locator.cookieRepository.hasLoginCookies()) {
            ApiLog.record("未登录 → 引导扫码")
            showLogin()
        } else {
            // 已登录但未设置音乐文件夹 → 直接引导选择
            maybeShowFolderPicker()
        }
    }

    override fun onStart() {
        super.onStart()
        buildController()
    }

    /**
     * 控制器只在 Activity 销毁时释放，onStop 时不释放：电视进入屏保/按 Home 键都会触发 onStop，
     * 若此时断开，PlayerManager 的补队列（连播扩展）与出错重试全部失效，
     * 表现为「顺序播放播几首就停」「单曲循环遇到一次链接失效就停」。
     */
    override fun onDestroy() {
        manager.detach()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }

    // ---- MediaController 生命周期 ----

    private fun buildController() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                manager.attach(c)
                updateMiniBar()
                refreshContinueRow()
            } catch (e: Exception) {
                Log.e(TAG, "MediaController 建立失败", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- 遥控器按键 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { manager.togglePlayPause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { controller?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { controller?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { manager.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { manager.previous(); return true }
                KeyEvent.KEYCODE_BACK -> if (handleBack()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(): Boolean {
        folderPickerFragment?.let { if (it.isAdded && !it.isHidden) { hideFolderPicker(); return true } }
        loginFragment?.let { if (it.isAdded && !it.isHidden) { hideLogin(); return true } }
        playerFragment?.let { if (it.isAdded && !it.isHidden) { hidePlayer(); return true } }
        (listFragments.getOrNull(selectedTab) as? FolderFragment)?.let { folderFragment ->
            if (folderFragment.onBackPressed()) return true
        }
        return false
    }

    // ---- 侧栏 ----

    private fun buildSidebar() {
        val inflater = layoutInflater

        // 「继续播放」行（有历史队列且当前无队列时显示）
        val cont = inflater.inflate(R.layout.item_sidebar, sidebar, false)
        continueLabel = cont.findViewById<TextView>(R.id.sidebar_label).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        }
        cont.visibility = View.GONE
        cont.setOnClickListener { onContinueClicked() }
        cont.bindFocusScale()
        sidebar.addView(cont)
        continueRow = cont

        listOf(
            R.string.tab_category to 0,
            R.string.tab_search to 2,
            R.string.tab_settings to 3,
            R.string.tab_log to 4,
        ).forEach { (res, index) ->
            val row = inflater.inflate(R.layout.item_sidebar, sidebar, false)
            row.findViewById<TextView>(R.id.sidebar_label).setText(res)
            row.setOnClickListener { selectTab(index) }
            row.bindFocusScale()
            // 焦点延时切换：停留 300ms 才真正切页，路过不触发
            row.setOnFocusChangeListener { _, hasFocus ->
                pendingSwitch?.let { sidebarHandler.removeCallbacks(it) }
                pendingSwitch = null
                if (hasFocus) {
                    val switch = Runnable { selectTab(index) }
                    pendingSwitch = switch
                    sidebarHandler.postDelayed(switch, SIDE_SWITCH_DELAY_MS)
                }
            }
            sidebar.addView(row)
            tabRows.add(row to index)
        }
        highlightTab(0)
    }

    /** 登录/引导页显示期间禁用侧栏，防止焦点误触把引导页切掉 */
    private fun setSidebarActive(active: Boolean) {
        cancelPendingSwitch()
        sidebar.isEnabled = active
        sidebar.alpha = if (active) 1f else 0.35f
        for (i in 0 until sidebar.childCount) sidebar.getChildAt(i).isEnabled = active
        if (!active) sidebar.clearFocus()
    }

    private val sidebarHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSwitch: Runnable? = null

    private fun cancelPendingSwitch() {
        pendingSwitch?.let { sidebarHandler.removeCallbacks(it) }
        pendingSwitch = null
    }

    private fun highlightTab(index: Int) {
        tabRows.forEach { (row, i) ->
            row.findViewById<TextView>(R.id.sidebar_label).setTextColor(
                ContextCompat.getColor(
                    this,
                    if (i == index) R.color.accent else R.color.text_primary,
                )
            )
        }
    }

    private fun selectTab(index: Int) {
        if (!sidebar.isEnabled) return // 登录/引导页显示期间不响应
        selectedTab = index
        highlightTab(index)

        val fragment = listFragments.getOrNull(index) ?: when (index) {
            0 -> CategoryFragment()
            1 -> FolderFragment()
            2 -> SearchFragment()
            4 -> LogFragment()
            else -> SettingsFragment()
        }.also { listFragments[index] = it }

        supportFragmentManager.beginTransaction().apply {
            listFragments.forEachIndexed { i, f ->
                if (f != null && f.isAdded) {
                    if (i == index) show(f) else hide(f)
                }
            }
            playerFragment?.let { if (it.isAdded) hide(it) }
            loginFragment?.let { if (it.isAdded) hide(it) }
            folderPickerFragment?.let { if (it.isAdded) hide(it) }
            if (!fragment.isAdded) add(R.id.fragment_container, fragment)
        }.commit()
        updateMiniBar()
    }

    // ---- 播放页切换 ----

    override fun openPlayerScreen() = showPlayer()

    private fun showPlayer() {
        com.music115.tv.api.ApiLog.record("进入播放页")
        setSidebarActive(false)
        val fm = supportFragmentManager
        val pf = playerFragment ?: PlayerFragment().also { playerFragment = it }
        fm.beginTransaction().apply {
            listFragments.forEach { f -> f?.let { if (it.isAdded) hide(it) } }
            loginFragment?.let { if (it.isAdded) hide(it) }
            folderPickerFragment?.let { if (it.isAdded) hide(it) }
            if (pf.isAdded) show(pf) else add(R.id.fragment_container, pf)
        }.commit()
        miniContainer.visibility = View.GONE
    }

    private fun hidePlayer() {
        setSidebarActive(true)
        playerFragment?.let { pf ->
            if (pf.isAdded) supportFragmentManager.beginTransaction().hide(pf).commit()
        }
        updateMiniBar()
    }

    // ---- 音乐文件夹引导页 ----

    override fun showFolderPicker() {
        setSidebarActive(false)
        val fm = supportFragmentManager
        val pf = folderPickerFragment ?: com.music115.tv.ui.FolderPickerFragment().also { folderPickerFragment = it }
        fm.beginTransaction().apply {
            listFragments.forEach { f -> f?.let { if (it.isAdded) hide(it) } }
            playerFragment?.let { if (it.isAdded) hide(it) }
            loginFragment?.let { if (it.isAdded) hide(it) }
            if (pf.isAdded) show(pf) else add(R.id.fragment_container, pf)
        }.commit()
    }

    override fun hideFolderPicker() {
        setSidebarActive(true)
        folderPickerFragment?.let { pf ->
            if (pf.isAdded) supportFragmentManager.beginTransaction().hide(pf).commit()
        }
        // 回到当前标签页，并重载列表（曲库目录可能已变更）
        listFragments.getOrNull(selectedTab)?.let { f ->
            if (f.isAdded) supportFragmentManager.beginTransaction().show(f).commit()
        }
        reloadListFragments()
        updateMiniBar()
    }

    /** 重载所有已创建的列表页（登录成功/曲库目录变更后用） */
    private fun reloadListFragments() {
        listFragments.forEachIndexed { i, f ->
            if (i != 2 && f is ListBaseFragment && f.isAdded) f.loadInitial(refresh = true)
        }
    }

    /** 登录成功后：未设定音乐文件夹则引导设置 */
    private fun maybeShowFolderPicker() {
        lifecycleScope.launch {
            if (locator.cookieRepository.hasLoginCookies() &&
                locator.playbackPrefs.musicFolder() == null
            ) {
                ApiLog.record("音乐文件夹未设置 → 引导选择")
                showFolderPicker()
            }
        }
    }

    // ---- 登录页切换 ----

    override fun showLogin() {
        setSidebarActive(false)
        val fm = supportFragmentManager
        val lf = loginFragment ?: LoginFragment().also { loginFragment = it }
        fm.beginTransaction().apply {
            listFragments.forEach { f -> f?.let { if (it.isAdded) hide(it) } }
            playerFragment?.let { if (it.isAdded) hide(it) }
            folderPickerFragment?.let { if (it.isAdded) hide(it) }
            if (lf.isAdded) show(lf) else add(R.id.fragment_container, lf)
        }.commit()
    }

    override fun hideLogin() {
        setSidebarActive(true)
        val lf = loginFragment ?: return
        val current = listFragments.getOrNull(selectedTab)
        supportFragmentManager.beginTransaction().apply {
            if (lf.isAdded) hide(lf)
            current?.let { if (it.isAdded) show(it) }
        }.commit()
        // 登录前页面可能带着「未登录/过期」的旧状态，登录成功后统一重新加载
        // （搜索页除外：等用户输入关键词后自行触发）
        reloadListFragments()
        // 登录成功后若尚未设置音乐文件夹，引导用户选择
        maybeShowFolderPicker()
    }

    // ---- 迷你播放条 ----

    private fun setupMiniBar() {
        val bar = layoutInflater.inflate(R.layout.mini_player_bar, miniContainer, false)
        miniTitle = bar.findViewById(R.id.mini_title)
        miniPlayPause = bar.findViewById<ImageButton>(R.id.mini_play_pause).apply {
            setOnClickListener { manager.togglePlayPause() }
        }
        bar.setOnClickListener { showPlayer() }
        bar.bindFocusScale()
        miniContainer.addView(bar)
    }

    private fun updateMiniBar() {
        val playerShown = playerFragment?.isAdded == true && playerFragment?.isHidden == false
        miniContainer.visibility =
            if (manager.hasQueue && !playerShown) View.VISIBLE else View.GONE
        val group = manager.currentGroup()
        miniTitle?.text = group?.displayName
            ?: controller?.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        miniPlayPause?.setImageResource(
            if (manager.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    // ---- 继续播放 ----

    private fun refreshContinueRow() {
        if (manager.hasQueue) {
            continueRow?.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val title = manager.savedQueueTitle()
            continueRow?.visibility =
                if (title != null && !manager.hasQueue) View.VISIBLE else View.GONE
            title?.let { continueLabel?.text = getString(R.string.continue_play, it) }
        }
    }

    private fun onContinueClicked() {
        if (manager.controller == null) {
            Toast.makeText(this, "播放器初始化中，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (manager.restoreSavedQueue()) showPlayer()
        }
    }

    // ---- 会话过期自动处理 ----

    private fun observeSession() {
        lifecycleScope.launch {
            locator.cookieRepository.sessionState.collect { state ->
                if (state == SessionState.EXPIRED && !sessionHandling) {
                    sessionHandling = true
                    if (locator.cookieRepository.hasLoginCookies()) {
                        // 先尝试无感刷新 WAF 挑战 Cookie
                        WafRefresher.refresh(this@MainActivity) { ok ->
                            sessionHandling = false
                            if (ok) {
                                locator.listingRepository.invalidate()
                                listFragments.forEachIndexed { i, f ->
                                    if (i != 2 && f is ListBaseFragment && f.isAdded) {
                                        f.loadInitial(refresh = true)
                                    }
                                }
                            } else {
                                showLogin()
                            }
                        }
                    } else {
                        sessionHandling = false
                        showLogin()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 侧栏焦点停留多久才切换页面 */
        private const val SIDE_SWITCH_DELAY_MS = 300L
    }
}
