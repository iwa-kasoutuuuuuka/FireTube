package com.firetube.tv.data.network

import com.google.gson.Gson
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Fire TV 向け統合ネットワーククライアント
 * - 共有 ConnectionPool による HTTP/2 ソケット再利用と TLS ハンドシェイク省略
 * - 各種 API クライアント（Piped, InnerTube, SponsorBlock, NewPipe）の接続を集約
 * - 低RAM環境でのバックグラウンドスレッド浪費を抑制
 */
object NetworkClient {

    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
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
