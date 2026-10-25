package com.music115.tv.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music115.tv.App
import com.music115.tv.net.Http

/**
 * WAF 挑战 Cookie 刷新器：会话 Cookie 还在但挑战 Cookie 失效/缺失时，
 * 用隐藏 WebView 重访一个轻量真实接口，让 JS 挑战重新跑一遍并收割新 Cookie。
 */
object WafRefresher {

    private const val REFRESH_URL =
        "https://webapi.115.com/files?aid=1&cid=0&o=user_ptime&asc=0&offset=0&show_dir=1&limit=1&format=json"

    @SuppressLint("SetJavaScriptEnabled")
    fun refresh(activity: Activity, onResult: (Boolean) -> Unit) {
        val locator = (activity.application as App).locator
        val cm = CookieManager.getInstance()

        // 注入现有 Cookie，让 WebView 带着 Cookie 过挑战
        locator.cookieRepository.snapshot().forEach { (k, v) ->
            cm.setCookie("https://www.115.com/", "$k=$v; Domain=.115.com; Path=/")
            cm.setCookie("https://webapi.115.com/", "$k=$v; Path=/")
        }
        cm.flush()

        val webView = WebView(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
            alpha = 0f
            visibility = android.view.View.INVISIBLE
            settings.apply {
                userAgentString = Http.APP_USER_AGENT
                javaScriptEnabled = true
                domStorageEnabled = true
            }
        }
        activity.addContentView(
            webView,
            android.view.ViewGroup.LayoutParams(1, 1),
        )

        // 只允许一个完成路径：页面加载完 3 秒后收割，或 15 秒超时兜底
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishAndReport() {
            if (!finished.compareAndSet(false, true)) return
            val raw = cm.getCookie("https://webapi.115.com/")
            val got = raw?.split(";")?.mapNotNull { p ->
                p.substringBefore('=').trim().takeIf { it.isNotBlank() }
            } ?: emptyList()
            val hasHash = got.any { it.length == 32 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
            com.music115.tv.api.ApiLog.record(
                "WAF挑战收割：Cookie名=$got " + if (hasHash) "含哈希Cookie" else "无哈希Cookie!"
            )
            if (raw != null) locator.cookieRepository.merge(CookiePaster.parse(raw))
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
            onResult(locator.cookieRepository.hasLoginCookies())
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Handler(Looper.getMainLooper()).postDelayed({ finishAndReport() }, 3000)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            com.music115.tv.api.ApiLog.record("WAF挑战：15秒超时兜底触发")
            finishAndReport()
        }, 15_000)
        webView.loadUrl(REFRESH_URL)
    }
}
