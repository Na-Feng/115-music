package com.music115.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.music115.tv.App
import com.music115.tv.BuildConfig
import com.music115.tv.R
import com.music115.tv.api.ApiLog
import com.music115.tv.auth.CookiePaster
import com.music115.tv.ui.MainHost
import kotlinx.coroutines.launch

/** 设置页：音质偏好、登录管理、调试 Cookie 粘贴（仅 debug） */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val locator by lazy { (requireActivity().application as App).locator }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.button_relogin).setOnClickListener {
            (activity as? MainHost)?.showLogin()
        }
        view.findViewById<Button>(R.id.button_change_folder).setOnClickListener {
            (activity as? MainHost)?.showFolderPicker()
        }
        view.findViewById<Button>(R.id.button_logout).setOnClickListener {
            lifecycleScope.launch {
                locator.cookieRepository.clear()
                locator.playbackPrefs.clearMusicFolder()
                locator.listingRepository.onMusicFolderChanged()
                locator.listingRepository.invalidate()
                locator.playerManager.controller?.apply {
                    clearMediaItems()
                    stop()
                }
                refreshCookieStatus(view)
                Toast.makeText(requireContext(), "已登出", Toast.LENGTH_SHORT).show()
            }
        }

        val debugSection = view.findViewById<View>(R.id.debug_section)
        debugSection.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        if (BuildConfig.DEBUG) {
            view.findViewById<Button>(R.id.button_save_cookie).setOnClickListener {
                val parsed = CookiePaster.parse(
                    view.findViewById<EditText>(R.id.cookie_input).text.toString()
                )
                if (parsed.isEmpty()) {
                    Toast.makeText(requireContext(), "Cookie 为空或格式不对", Toast.LENGTH_SHORT).show()
                } else {
                    locator.cookieRepository.replaceAll(parsed)
                    locator.listingRepository.invalidate()
                    refreshCookieStatus(view)
                    Toast.makeText(requireContext(), "Cookie 已保存（${parsed.size} 项）", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<TextView>(R.id.about_text).text =
            "115音乐 TV v${BuildConfig.VERSION_NAME}\n" +
                "Cookie 保存在本机私有目录，不会上传。\n" +
                "接口为 115 网页版内部接口，请勿高频请求，注意账号风控。"

        // ---- 诊断区 ----
        refreshDiag(view)
        view.findViewById<Button>(R.id.button_test_api).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val res = locator.api.files(cid = "0", offset = 0, limit = 1)
                    Toast.makeText(
                        requireContext(),
                        "接口正常：count=${res.count}，首条=${res.data.firstOrNull()?.n ?: "无"}",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "接口失败：${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                refreshDiag(view)
            }
        }
        view.findViewById<Button>(R.id.button_clear_log).setOnClickListener {
            ApiLog.clear()
            refreshDiag(view)
        }

        refreshCookieStatus(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { refreshDiag(it) }
    }

    private fun refreshDiag(view: View) {
        val repo = locator.cookieRepository
        val keys = repo.snapshot().keys.sorted()
        view.findViewById<TextView>(R.id.diag_cookie).text =
            "App v${BuildConfig.VERSION_NAME}(code ${BuildConfig.VERSION_CODE})；" +
                "会话状态：${repo.sessionState.value}；Cookie 键：${if (keys.isEmpty()) "无" else keys.joinToString()}"
        view.findViewById<TextView>(R.id.diag_log).text = ApiLog.dump()
    }

    private fun refreshCookieStatus(view: View) {
        val repo = locator.cookieRepository
        view.findViewById<TextView>(R.id.cookie_status).text =
            if (repo.hasLoginCookies()) {
                "已登录（${repo.snapshot().size} 个 Cookie）"
            } else {
                "未登录 —— 请扫码登录，或（调试）粘贴浏览器 Cookie"
            }
    }
}
