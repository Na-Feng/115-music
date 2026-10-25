package com.music115.tv.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.music115.tv.data.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val COOKIES_KEY = stringPreferencesKey("cookies_json")

/**
 * 115 Web 会话 Cookie 的唯一权威来源（内存 Map + DataStore 持久化）。
 * 包含登录态（UID/CID/SEID/USERSESSIONID）、WAF 挑战哈希 Cookie、acw_tc 等，
 * 全部统一存放 —— 请求时全量携带（实测对 webapi 与 CDN 都有效）。
 */
class CookieRepository(private val context: Context, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = LinkedHashMap<String, String>()
    val sessionState = MutableStateFlow(SessionState.LOGGED_OUT)

    init {
        runBlocking { load() }
    }

    private suspend fun load() {
        val prefs = context.appDataStore.data.first()
        val raw = prefs[COOKIES_KEY]
        if (raw != null) {
            try {
                cookies.clear()
                cookies.putAll(json.decodeFromString<Map<String, String>>(raw))
            } catch (_: Exception) {
            }
        }
        sessionState.value =
            if (hasLoginCookies()) SessionState.OK else SessionState.LOGGED_OUT
    }

    fun header(): String = synchronized(cookies) {
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun snapshot(): Map<String, String> = synchronized(cookies) { cookies.toMap() }

    fun hasLoginCookies(): Boolean = synchronized(cookies) {
        cookies.containsKey("UID") && cookies.containsKey("CID") && cookies.containsKey("SEID")
    }

    fun replaceAll(map: Map<String, String>) {
        synchronized(cookies) {
            cookies.clear()
            cookies.putAll(map)
        }
        persist()
        sessionState.value = if (hasLoginCookies()) SessionState.OK else SessionState.LOGGED_OUT
    }

    /** 合并（用于收割 Set-Cookie / WebView 挑战 Cookie），不改变会话状态判定 */
    fun merge(map: Map<String, String>) {
        if (map.isEmpty()) return
        synchronized(cookies) {
            map.forEach { (k, v) -> cookies[k] = v }
        }
        persist()
        if (sessionState.value == SessionState.LOGGED_OUT && hasLoginCookies()) {
            sessionState.value = SessionState.OK
        }
    }

    suspend fun clear() {
        synchronized(cookies) { cookies.clear() }
        persist()
        sessionState.value = SessionState.LOGGED_OUT
    }

    private fun persist() {
        val snapshot = snapshot()
        scope.launch {
            context.appDataStore.edit { it[COOKIES_KEY] = json.encodeToString(snapshot) }
        }
    }
}
