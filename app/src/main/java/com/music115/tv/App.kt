package com.music115.tv

import android.app.Application
import android.content.Context
import com.music115.tv.api.Api115
import com.music115.tv.auth.CookieRepository
import com.music115.tv.data.ListingRepository
import com.music115.tv.data.PlaybackPrefs
import com.music115.tv.net.AuthInterceptor
import com.music115.tv.playback.DownloadUrlResolver
import com.music115.tv.playback.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        com.music115.tv.api.CrashReporter.install(this)
        locator = ServiceLocator(this)
        // 上次崩溃的堆栈灌入诊断日志（日志页可见）
        com.music115.tv.api.CrashReporter.reportPending(this)
        com.music115.tv.api.ApiLog.record(
            "App 启动 v${com.music115.tv.BuildConfig.VERSION_NAME}(code ${com.music115.tv.BuildConfig.VERSION_CODE})"
        )
    }
}

/** 手动 DI：全应用单例组装点 */
class ServiceLocator(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 全局后台任务作用域：未捕获的异常统一拦截并记录，绝不冒泡杀死进程。
     * 这些都是维护性任务（补队列/预取/存进度），失败本就应当只留痕、不影响使用。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                com.music115.tv.api.ApiLog.record(
                    "后台任务异常(已拦截) ${e.javaClass.simpleName}: ${e.message}"
                )
            }
    )

    val cookieRepository = CookieRepository(appContext, scope)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(cookieRepository))
        .build()

    val api = Api115(okHttpClient, cookieRepository)
    val playbackPrefs = PlaybackPrefs(appContext, scope)
    val listingRepository = ListingRepository(api, playbackPrefs)
    val downloadUrlResolver = DownloadUrlResolver(api)
    val playerManager = PlayerManager(this, scope)
}
