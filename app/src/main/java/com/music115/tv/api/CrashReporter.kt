package com.music115.tv.api

import android.content.Context
import java.io.File

/**
 * 全局崩溃捕获：进程崩溃时把完整堆栈 + 最近 ApiLog 写入 filesDir/crashes，
 * 下次启动把未上报的记录灌入 ApiLog（日志页可见）——无 adb 的电视也能排查闪退。
 */
object CrashReporter {

    private const val PREFIX = "crash-"
    private const val KEEP = 5

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 启动时调用：上次崩溃的记录灌入 ApiLog，随后标记为已上报 */
    fun reportPending(app: Context) {
        val dir = File(app.filesDir, "crashes")
        val pending = dir.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedBy { it.lastModified() } ?: return
        pending.forEach { f ->
            ApiLog.record("【上次崩溃】${f.readText().take(3500)}")
            f.renameTo(File(dir, f.name + ".done"))
        }
        // 已上报的留最近 2 份，其余清理
        dir.listFiles { f -> f.name.endsWith(".done") }
            ?.sortedBy { it.lastModified() }
            ?.dropLast(2)
            ?.forEach { it.delete() }
    }

    private fun write(app: Context, thread: Thread, error: Throwable) {
        val dir = File(app.filesDir, "crashes").apply { mkdirs() }
        val text = buildString {
            appendLine("时间: ${ApiLog.now()}  线程: ${thread.name}")
            appendLine(error.stackTraceToString())
            appendLine("---- 最近操作日志 ----")
            appendLine(ApiLog.dump())
        }
        File(dir, "$PREFIX${System.currentTimeMillis()}.txt").writeText(text)
        // 只保留最近 KEEP 份
        dir.listFiles { f -> f.name.startsWith(PREFIX) }
            ?.sortedBy { it.name }
            ?.dropLast(KEEP)
            ?.forEach { it.delete() }
    }
}
