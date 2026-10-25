package com.music115.tv.playback

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import com.music115.tv.ServiceLocator
import com.music115.tv.net.Http
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * ExoPlayer 数据源：
 * - MediaItem URI 为 "115://<pickcode>" 时，开播时才解析真实 CDN 直链（防风控节流）；
 * - 请求带 Cookie + 统一 UA（CDN 必须，否则 403 "no cookie"）；
 * - 403 时失效缓存、重取直链、原位重试一次。
 */
class One15DataSourceFactory(private val locator: ServiceLocator) : DataSource.Factory {
    override fun createDataSource(): DataSource = One15DataSource(locator)
}

class One15DataSource(private val locator: ServiceLocator) : BaseDataSource(false) {

    private var upstream: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != "115") {
            return openUpstream(dataSpec)
        }
        val pickcode = uri.host
            ?: throw IOException("非法的 115 播放 URI: $uri")
        return openResolved(dataSpec, pickcode, retry = true)
    }

    private fun openResolved(spec: DataSpec, pickcode: String, retry: Boolean): Long {
        // DataSource.open 运行在 ExoPlayer 加载线程，阻塞解析可接受
        val url = runBlocking { locator.downloadUrlResolver.resolve(pickcode) }
        return try {
            openUpstream(spec.buildUpon().setUri(Uri.parse(url)).build())
        } catch (e: InvalidResponseCodeException) {
            if (e.responseCode == 403 && retry) {
                locator.downloadUrlResolver.invalidate(pickcode)
                openResolved(spec, pickcode, retry = false)
            } else {
                throw e
            }
        }
    }

    private fun openUpstream(spec: DataSpec): Long {
        val dataSource = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent(Http.APP_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to locator.cookieRepository.header(),
                    "Referer" to Http.REFERER,
                )
            )
            .createDataSource()
        upstream = dataSource
        return dataSource.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
        upstream?.read(buffer, offset, readLength)
            ?: throw IOException("数据源未打开")

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        upstream?.close()
        upstream = null
    }
}
