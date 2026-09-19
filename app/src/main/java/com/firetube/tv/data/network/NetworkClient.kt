package com.firetube.tv.data.network

import com.google.gson.Gson
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Fire TV 向け統合ネットワーククライアント
 * - 共有 ConnectionPool による HTTP/2 ソケット再利用と TLS ハンドシェイク省略
 * - Wi-Fi 6 / 4K Max 向けの 16並列ホスト通信 (DASH映像+音声+サムネイルのノンブロッキング並列ロード)
 * - 各種 API クライアント（Piped, InnerTube, SponsorBlock, NewPipe）の接続を集約
 */
object NetworkClient {

    private val connectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.header("User-Agent") == null) {
                original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }
        .dns(FastDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    val gson: Gson by lazy {
        Gson()
    }
}
