package com.firetube.tv.data.innertube

import android.util.Log
import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.model.VideoStream
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube InnerTube API 直接通信クライアント
 * HTMLスクレイピングを行わず、公式JSON APIと直接通信するため、
 * DOM構造変更によるパースエラー（ParsingException）が一切発生しない高耐久クライアント
 */
object InnerTubeClient {

    private const val TAG = "InnerTubeClient"
    private const val BASE_URL = "https://www.youtube.com/youtubei/v1"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = com.firetube.tv.data.network.NetworkClient.client
    private val gson = com.firetube.tv.data.network.NetworkClient.gson

    private fun buildContext(): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("hl", "ja")
            addProperty("gl", "JP")
            addProperty("clientName", "WEB")
            addProperty("clientVersion", "2.20240401.01.00")
            addProperty("utcOffsetMinutes", 540)
        }
        return JsonObject().apply {
            add("client", clientObj)
        }
    }

    /**
     * トレンド（急上昇）動画一覧の取得
     */
    suspend fun getTrendingVideos(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", buildContext())
                addProperty("browseId", "FEtrending")
            }

            val request = Request.Builder()
                .url("$BASE_URL/browse")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))

                val json = gson.fromJson(body, JsonObject::class.java)
                val items = parseVideoRenderers(json)
                Log.i(TAG, "InnerTube fetched ${items.size} trending videos successfully.")
                Result.success(items)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "InnerTube getTrendingVideos failed", e)
            Result.failure(e)
        }
    }

    /**
     * チャンネル動画一覧取得
     */
    suspend fun getChannelVideos(channelIdOrHandle: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val browseId = if (channelIdOrHandle.startsWith("UC")) {
                channelIdOrHandle
            } else {
                channelIdOrHandle.substringAfterLast("/").substringAfterLast("@")
            }

            val payload = JsonObject().apply {
                add("context", buildContext())
                addProperty("browseId", browseId)
            }

            val request = Request.Builder()
                .url("$BASE_URL/browse")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))

                val json = gson.fromJson(body, JsonObject::class.java)
                val items = parseVideoRenderers(json)
                Log.i(TAG, "InnerTube fetched ${items.size} channel videos for $channelIdOrHandle.")
                Result.success(items)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "InnerTube getChannelVideos failed", e)
            Result.failure(e)
        }
    }

    /**
     * 動画検索
     */
    suspend fun searchVideos(query: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", buildContext())
                addProperty("query", query)
            }

            val request = Request.Builder()
                .url("$BASE_URL/search")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))

                val json = gson.fromJson(body, JsonObject::class.java)
                val items = parseVideoRenderers(json)
                Log.i(TAG, "InnerTube search found ${items.size} videos for '$query'.")
                Result.success(items)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "InnerTube search failed", e)
            Result.failure(e)
        }
    }

    /**
     * 再生中の動画の「関連動画（Up Next）」一覧を取得
     */
    suspend fun getUpNextVideos(videoId: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", buildContext())
                addProperty("videoId", videoId)
            }

            val request = Request.Builder()
                .url("$BASE_URL/next")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))

                val json = gson.fromJson(body, JsonObject::class.java)
                val items = parseVideoRenderers(json)
                Log.i(TAG, "InnerTube fetched ${items.size} Up Next videos for $videoId.")
                Result.success(items)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "InnerTube getUpNextVideos failed", e)
            Result.failure(e)
        }
    }

    /**
     * 再帰的に JSON 内の videoRenderer を探索して VideoItem にマッピング
     */
    private fun parseVideoRenderers(root: JsonObject): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        findVideoRenderersRecursive(root, result)
        return result
    }

    private fun findVideoRenderersRecursive(element: JsonObject, result: MutableList<VideoItem>) {
        for (entry in element.entrySet()) {
            val key = entry.key
            val value = entry.value
            if ((key == "videoRenderer" || key == "compactVideoRenderer" || key == "gridVideoRenderer") && value.isJsonObject) {
                parseSingleRenderer(value.asJsonObject)?.let { item ->
                    if (result.none { it.id == item.id }) {
                        result.add(item)
                    }
                }
            } else if (value.isJsonObject) {
                findVideoRenderersRecursive(value.asJsonObject, result)
            } else if (value.isJsonArray) {
                for (subElem in value.asJsonArray) {
                    if (subElem.isJsonObject) {
                        findVideoRenderersRecursive(subElem.asJsonObject, result)
                    }
                }
            }
        }
    }

    private fun parseSingleRenderer(obj: JsonObject): VideoItem? {
        try {
            val videoId = obj.get("videoId")?.asString ?: return null
            val titleObj = obj.getAsJsonObject("title")
            val title = titleObj?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
                ?: titleObj?.get("simpleText")?.asString
                ?: "No title"

            val ownerObj = obj.getAsJsonObject("ownerText")
                ?: obj.getAsJsonObject("shortBylineText")
                ?: obj.getAsJsonObject("longBylineText")
            val firstRun = ownerObj?.getAsJsonArray("runs")?.get(0)?.asJsonObject
            val uploaderName = firstRun?.get("text")?.asString ?: "Channel"

            val browseEndpoint = firstRun?.getAsJsonObject("navigationEndpoint")?.getAsJsonObject("browseEndpoint")
            val uploaderUrl = browseEndpoint?.get("canonicalBaseUrl")?.asString
                ?: browseEndpoint?.get("browseId")?.asString

            val thumbnails = obj.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
            val thumbUrl = thumbnails?.lastOrNull()?.asJsonObject?.get("url")?.asString ?: ""

            val lengthObj = obj.getAsJsonObject("lengthText")
            val lengthText = lengthObj?.get("simpleText")?.asString
                ?: lengthObj?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
            val durationSec = parseDurationToSeconds(lengthText)

            return VideoItem(
                id = videoId,
                title = title,
                uploaderName = uploaderName,
                uploaderUrl = uploaderUrl,
                thumbnailUrl = thumbUrl,
                durationSeconds = durationSec
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseDurationToSeconds(text: String?): Long {
        if (text == null) return 0L
        val parts = text.split(":")
        var sec = 0L
        for (p in parts) {
            sec = sec * 60 + (p.toLongOrNull() ?: 0L)
        }
        return sec
    }
}
