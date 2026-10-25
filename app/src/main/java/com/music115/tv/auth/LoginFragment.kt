package com.music115.tv.auth

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.music115.tv.App
import com.music115.tv.R
import com.music115.tv.ServiceLocator
import com.music115.tv.api.ApiLog
import com.music115.tv.ui.MainHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * 原生扫码登录（与 115 网页版登录页同一套 qrcodeapi 接口，均为实测/对照 P115Client 实现）：
 * 1. GET  qrcodeapi.115.com/api/1.0/web/1.0/token/            → uid/time/sign + 二维码内容
 * 2. GET  qrcodeapi.115.com/get/status/?uid&time&sign          → 0 未扫 1 已扫 2 已确认 -1/-2 过期/取消
 * 3. POST qrcodeapi.115.com/app/1.0/web/1.0/login/qrcode/      → account={uid}，Cookie 从 Set-Cookie 收割
 * 4. 登录 Cookie 入库后，用隐藏 WebView 过 WAF 挑战补哈希 Cookie
 */
class LoginFragment : Fragment(R.layout.fragment_login) {

    private val locator: ServiceLocator by lazy {
        (requireActivity().application as App).locator
    }

    private var qrView: ImageView? = null
    private var statusText: TextView? = null
    private var loginJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        qrView = view.findViewById(R.id.qr_view)
        statusText = view.findViewById(R.id.login_status)
        view.findViewById<Button>(R.id.button_close).setOnClickListener {
            (activity as? MainHost)?.hideLogin()
        }
        view.findViewById<Button>(R.id.button_refresh_qr).setOnClickListener { startLogin() }
        startLogin()
    }

    private fun startLogin() {
        com.music115.tv.api.ApiLog.record("扫码页 startLogin")
        loginJob?.cancel()
        loginJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                status("正在获取二维码…")
                val token = fetchToken()
                qrView?.setImageBitmap(qrBitmap(token.content, 720))
                status("请用 115 手机 App 扫描二维码")
                var confirmed = false
                while (isActive) {
                    delay(2000)
                    when (pollStatus(token)) {
                        0 -> Unit
                        1 -> status("已扫码，请在手机上确认登录")
                        2 -> { confirmed = true; break }
                        else -> { status("二维码已过期或被取消，请点「刷新二维码」"); return@launch }
                    }
                }
                if (!confirmed) return@launch

                status("登录中…")
                val cookies = loginConfirm(token)
                if (!cookies.containsKey("UID") || !cookies.containsKey("CID") || !cookies.containsKey("SEID")) {
                    status("登录失败：未取得 Cookie（若提示 IP 异常请稍后再试）")
                    return@launch
                }
                locator.cookieRepository.replaceAll(cookies)

                status("正在完成安全校验…")
                WafRefresher.refresh(requireActivity()) { ok ->
                    locator.listingRepository.invalidate()
                    ApiLog.record("WAF 校验：${if (ok) "通过" else "未通过（播放可能403，可在设置页诊断）"}")
                    if (ok) {
                        Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "登录成功，但安全校验未完成", Toast.LENGTH_LONG).show()
                    }
                    // 登录 Cookie 已有效，安全校验失败也照常进入（列表接口不强依赖哈希 Cookie）
                    (activity as? MainHost)?.hideLogin()
                }
            } catch (e: Exception) {
                status("出错：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun status(text: String) {
        statusText?.text = text
    }

    // ---- qrcodeapi 接口 ----

    private data class QrToken(
        val uid: String,
        val time: Long,
        val sign: String,
        val content: String,
    )

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val started = android.os.SystemClock.elapsedRealtime()
        val request = Request.Builder().url(url).build()
        try {
            locator.okHttpClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: throw IllegalStateException("空响应 (HTTP ${resp.code})")
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                ApiLog.recordRequest(
                    "登录", url, resp.code,
                    android.os.SystemClock.elapsedRealtime() - started, "ok",
                )
                text
            }
        } catch (e: Exception) {
            ApiLog.recordRequest(
                "登录", url, 0,
                android.os.SystemClock.elapsedRealtime() - started,
                "EXC ${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
    }

    private suspend fun fetchToken(): QrToken {
        val body = httpGet("https://qrcodeapi.115.com/api/1.0/web/1.0/token/")
        val data = JSONObject(body).getJSONObject("data")
        val uid = data.getString("uid")
        // 注意：token 接口 qrcode 字段给的是 115.com 域名，但 115 App 只认 yun.115.com
        // 域名（官方登录页/p115client 渲染的服务器 PNG 实测即此内容），照抄会扫出 6 位数字
        return QrToken(
            uid = uid,
            time = data.getLong("time"),
            sign = data.getString("sign"),
            content = "https://yun.115.com/scan/dg-$uid",
        )
    }

    private suspend fun pollStatus(token: QrToken): Int {
        val body = httpGet(
            "https://qrcodeapi.115.com/get/status/?uid=${token.uid}&time=${token.time}&sign=${token.sign}"
        )
        val data = JSONObject(body).optJSONObject("data") ?: return 0
        return data.optInt("status", 0)
    }

    private suspend fun loginConfirm(token: QrToken): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://qrcodeapi.115.com/app/1.0/web/1.0/login/qrcode/")
            .post(FormBody.Builder().add("account", token.uid).build())
            .build()
        locator.okHttpClient.newCall(request).execute().use { resp ->
            val fromHeaders = resp.headers("Set-Cookie").mapNotNull { sc ->
                val pair = sc.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) pair[0].trim() to pair[1].trim() else null
            }.toMap()
            val text = resp.body?.string() ?: ""
            val fromBody = runCatching {
                JSONObject(text).optJSONObject("data")?.optJSONObject("cookie")?.let { obj ->
                    obj.keys().asSequence().associateWith { key -> obj.optString(key) }
                }
            }.getOrNull() ?: emptyMap()
            fromHeaders + fromBody
        }.also { cookies ->
            ApiLog.record("登录换Cookie：取得 ${cookies.keys.sorted()} " +
                if (setOf("UID", "CID", "SEID").all(cookies::containsKey)) "完整" else "不完整!")
        }
    }

    // ---- 本地二维码渲染（zxing） ----

    private suspend fun qrBitmap(content: String, size: Int): Bitmap = withContext(Dispatchers.Default) {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    override fun onDestroyView() {
        loginJob?.cancel()
        qrView = null
        statusText = null
        super.onDestroyView()
    }
}
