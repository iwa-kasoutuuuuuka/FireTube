package com.firetube.tv.data.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor 用の OkHttp3 ダウンローダー実装
 * Fire TV 向けに接続タイムアウトとプールを最適化
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    companion object {
        fun createDefault(): OkHttpDownloader {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            return OkHttpDownloader(client)
        }
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)

        headers.forEach { (name, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(name, value)
            }
        }

        // デフォルトのテレビ向け User-Agent 設定
        if (!headers.containsKey("User-Agent")) {
            requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }

        val requestBody = dataToSend?.toRequestBody(null)
        requestBuilder.method(httpMethod, requestBody)

        val call = client.newCall(requestBuilder.build())
        val response = call.execute()

        val responseBody = response.body?.string() ?: ""
        val responseHeaders = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            responseHeaders[name] = response.headers.values(name)
        }

        return Response(
            response.code,
            response.message,
            responseHeaders,
            responseBody,
            response.request.url.toString()
        )
    }
}
