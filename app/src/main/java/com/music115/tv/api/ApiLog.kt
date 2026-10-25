package com.music115.tv.api

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 请求诊断日志：环形缓冲最近 30 条，设置页「诊断」区展示。
 * 每条一行：时间 接口 → 结果（HTTP码/耗时/errNo/错误摘要）。
 */
object ApiLog {

    private const val MAX = 30
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    @Synchronized
    fun record(line: String) {
        while (lines.size >= MAX) lines.removeFirst()
        val stamped = "${fmt.format(Date())} $line"
        lines.addLast(stamped)
        android.util.Log.i("115Diag", line)
    }

    fun recordRequest(tag: String, url: String, httpCode: Int, costMs: Long, result: String) {
        val short = url.substringAfter("//").let {
            if (it.length > 90) it.take(90) + "…" else it
        }
        record("$tag $short → HTTP $httpCode ${costMs}ms $result")
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "（暂无请求记录）" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    fun now(): String = fmt.format(Date())
}
