package com.firetube.tv.data.network

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * YouTube CDN (googlevideo.com) 特化型 DataSource
 *
 * 【背景と根本原因】
 * YouTube の googlevideo.com CDN は、ボット対策や最新のストリーミング制限により、
 * `Range` ヘッダーのないリクエスト、または終端のないオープンエンドリクエスト (`Range: bytes=0-`)、
 * またはコンテンツ長を超えるレンジ要求に対して一律で HTTP 403 Forbidden を返却する仕様となっている。
 *
 * 【本クラスの解決策】
 * URL の `clen` パラメータおよび Content-Range レスポンスヘッダーからコンテンツ全体の厳密な長さを把握し、
 * 有界 Range チャンク (`bytes=START-END`) で安全に分割・順次要求。
 * なお、HLS プレイリスト/マニフェスト (.m3u8) はテキストデータであり Range 分割してはならないため、
 * 自動的に標準 upstream DataSource に委譲する。
 */
@OptIn(UnstableApi::class)
class GoogleVideoDataSource(
    private val client: OkHttpClient,
    private val upstream: DataSource,
    private val chunkSize: Long = 512 * 1024L // 512KB 有界チャンク（YouTube CDN の初期バースト制限に完全適合）
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "GoogleVideoDataSource"
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private var currentDataSpec: DataSpec? = null
    private var uri: Uri? = null
    private var isDelegated: Boolean = false

    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var totalLength: Long = C.LENGTH_UNSET.toLong()

    private var currentResponse: Response? = null
    private var currentStream: InputStream? = null
    private var currentChunkRemaining: Long = 0L
    private var transferActive: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        this.currentDataSpec = dataSpec
        this.uri = dataSpec.uri
        val host = dataSpec.uri.host.orEmpty()
        val isGoogleVideo = host.contains("googlevideo.com", ignoreCase = true)

        val uriStr = dataSpec.uri.toString()
        val isPlaylist = uriStr.contains(".m3u8", ignoreCase = true) ||
                uriStr.contains("playlist_type", ignoreCase = true) ||
                uriStr.contains("hls_variant", ignoreCase = true) ||
                uriStr.contains("/manifest/", ignoreCase = true)

        isDelegated = (!isGoogleVideo || isPlaylist)
        if (isDelegated) {
            // HLS プレイリストや通常 HTTP サーバー等は標準 DataSource に委譲
            return upstream.open(dataSpec)
        }

        transferInitializing(dataSpec)

        this.currentPosition = dataSpec.position
        // URL クエリの clen から全体のバイト数を即座に推定
        val clenParam = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        this.totalLength = clenParam ?: C.LENGTH_UNSET.toLong()

        openNextChunk()

        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            this.bytesRemaining = dataSpec.length
        } else {
            if (totalLength != C.LENGTH_UNSET.toLong()) {
                this.bytesRemaining = (totalLength - dataSpec.position).coerceAtLeast(0L)
            } else {
                this.bytesRemaining = C.LENGTH_UNSET.toLong()
            }
        }

        transferStarted(dataSpec)
        transferActive = true
        Log.i(TAG, "Opened stream for ${dataSpec.uri.path}: pos=$currentPosition, length=${dataSpec.length}, totalLength=$totalLength, bytesRemaining=$bytesRemaining")
        return this.bytesRemaining
    }

    private fun openNextChunk() {
        closeCurrentChunk()

        if (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength) {
            return
        }

        val start = currentPosition
        val end = if (totalLength != C.LENGTH_UNSET.toLong()) {
            minOf(start + chunkSize - 1, totalLength - 1)
        } else {
            start + chunkSize - 1
        }

        if (start > end) return

        val rangeHeader = "bytes=$start-$end"
        val request = Request.Builder()
            .url(uri.toString())
            .header("Range", rangeHeader)
            .header("User-Agent", DEFAULT_UA)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            val code = response.code
            val message = response.message
            response.close()
            Log.e(TAG, "GoogleVideo chunk request failed: HTTP $code ($message) for range $rangeHeader")
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                message,
                /* cause = */ null,
                /* responseHeaders = */ emptyMap(),
                currentDataSpec!!,
                /* responseBody = */ ByteArray(0)
            )
        }

        val contentRange = response.header("Content-Range")
        if (contentRange != null && totalLength == C.LENGTH_UNSET.toLong()) {
            val slashIdx = contentRange.lastIndexOf('/')
            if (slashIdx != -1) {
                totalLength = contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: C.LENGTH_UNSET.toLong()
            }
        }

        val body = response.body ?: throw IOException("Empty response body for chunk $rangeHeader")
        this.currentResponse = response
        this.currentStream = body.byteStream()
        val contentLength = body.contentLength()
        this.currentChunkRemaining = if (contentLength > 0) contentLength else (end - start + 1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (isDelegated) {
            return upstream.read(buffer, offset, length)
        }

        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        if (currentChunkRemaining <= 0L) {
            if (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength) {
                return C.RESULT_END_OF_INPUT
            }
            openNextChunk()
            if (currentChunkRemaining <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining, currentChunkRemaining).toInt()
        } else {
            minOf(length.toLong(), currentChunkRemaining).toInt()
        }

        val bytesRead = currentStream?.read(buffer, offset, bytesToRead) ?: -1
        if (bytesRead == -1) {
            if (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength) {
                return C.RESULT_END_OF_INPUT
            }
            openNextChunk()
            return read(buffer, offset, length)
        }

        currentPosition += bytesRead
        currentChunkRemaining -= bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }

        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? {
        return if (isDelegated) upstream.uri else uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return if (isDelegated) {
            upstream.responseHeaders
        } else {
            currentResponse?.headers?.toMultimap() ?: emptyMap()
        }
    }

    private fun closeCurrentChunk() {
        try {
            currentStream?.close()
        } catch (_: Exception) {}
        currentStream = null

        try {
            currentResponse?.close()
        } catch (_: Exception) {}
        currentResponse = null

        currentChunkRemaining = 0L
    }

    override fun close() {
        if (isDelegated) {
            upstream.close()
            return
        }

        closeCurrentChunk()
        if (transferActive) {
            transferActive = false
            transferEnded()
        }
    }

    /**
     * GoogleVideoDataSource 用の Factory
     */
    class Factory(
        private val client: OkHttpClient,
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = 512 * 1024L
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return GoogleVideoDataSource(
                client = client,
                upstream = upstreamFactory.createDataSource(),
                chunkSize = chunkSize
            )
        }
    }
}
