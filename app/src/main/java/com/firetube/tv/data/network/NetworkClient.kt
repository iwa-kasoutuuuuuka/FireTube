package com.firetube.tv.data.network

import com.google.gson.Gson
import com.firetube.tv.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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
            val response = chain.proceed(request)
            // ⑥ Release ビルド時は CDN ログを完全無効化（セグメント毎に発火するため高負荷）
            if (BuildConfig.DEBUG && request.url.host.contains("googlevideo.com")) {
                android.util.Log.d("NetworkClient", "GoogleVideo HTTP ${response.code} for: ${request.url.encodedPath}?${request.url.encodedQuery?.take(60)} | Range=${request.header("Range")} | UA=${request.header("User-Agent")?.take(30)}")
            }
            response
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

/**
 * OkHttp Call のキャンセル対応 suspend 実行
 * - 旧 execute() はスレッドをブロックし、コルーチンがキャンセルされても通信が最後まで続行していた
 * - 本拡張はコルーチンのキャンセルと同時に Call.cancel() を発火し、ソケットと帯域を即座に解放する
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try { cancel() } catch (_: Throwable) {}
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _ -> response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
