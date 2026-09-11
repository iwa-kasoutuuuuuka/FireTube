package com.firetube.tv.data.piped

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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

import com.firetube.tv.data.network.NetworkClient

/**
 * Piped API クライアント (セカンダリフォールバック)
 * YouTube公式APIやスクレイピングがブロックされた場合の代替通信路
 * 最速稼働インスタンスを先頭に配置し、共有 NetworkClient による高速通信を実現
 */
object PipedApiClient {

    private const val TAG = "PipedApiClient"

    // 実測最速の稼働インスタンスを優先配置（HTMLを返すWebフロントエンドを除外）
    private val INSTANCES = listOf(
        "https://api.piped.private.coffee",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.kavin.rocks"
    )

    private val client = NetworkClient.client
    private val gson = NetworkClient.gson

    /**
     * トレンド動画取得
     */
    suspend fun getTrendingVideos(region: String = "JP"): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        for (baseUrl in INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/trending?region=$region")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val jsonArray = gson.fromJson(body, JsonArray::class.java)
                    val items = mutableListOf<VideoItem>()

                    for (elem in jsonArray) {
                        if (!elem.isJsonObject) continue
                        val obj = elem.asJsonObject
                        val url = obj.get("url")?.asString ?: ""
                        val videoId = url.removePrefix("/watch?v=")
                        if (videoId.isEmpty()) continue

                        val title = obj.get("title")?.asString ?: "No title"
                        val uploader = obj.get("uploaderName")?.asString ?: "Channel"
                        val thumbnail = obj.get("thumbnail")?.asString ?: ""
                        val duration = obj.get("duration")?.asLong ?: 0L

                        items.add(
                            VideoItem(
                                id = videoId,
                                title = title,
                                uploaderName = uploader,
                                thumbnailUrl = thumbnail,
                                durationSeconds = duration
                            )
                        )
                    }

                    if (items.isNotEmpty()) {
                        Log.i(TAG, "Fetched ${items.size} trending videos from $baseUrl")
                        return@withContext Result.success(items)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $baseUrl failed: ${e.message}")
            }
        }
        Result.failure(Exception("All Piped instances failed"))
    }

    /**
     * 検索動画取得
     */
    suspend fun searchVideos(query: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        for (baseUrl in INSTANCES) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/search?q=$encodedQuery&filter=all")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val itemsArray = json.getAsJsonArray("items") ?: return@use
                    val items = mutableListOf<VideoItem>()

                    for (elem in itemsArray) {
                        if (!elem.isJsonObject) continue
                        val obj = elem.asJsonObject
                        val type = obj.get("type")?.asString ?: ""
                        if (type != "stream") continue

                        val url = obj.get("url")?.asString ?: ""
                        val videoId = url.removePrefix("/watch?v=")
                        if (videoId.isEmpty()) continue

                        val title = obj.get("title")?.asString ?: "No title"
                        val uploader = obj.get("uploaderName")?.asString ?: "Channel"
                        val thumbnail = obj.get("thumbnail")?.asString ?: ""
                        val duration = obj.get("duration")?.asLong ?: 0L

                        items.add(
                            VideoItem(
                                id = videoId,
                                title = title,
                                uploaderName = uploader,
                                thumbnailUrl = thumbnail,
                                durationSeconds = duration
                            )
                        )
                    }

                    if (items.isNotEmpty()) {
                        Log.i(TAG, "Search returned ${items.size} videos from $baseUrl")
                        return@withContext Result.success(items)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped search on $baseUrl failed: ${e.message}")
            }
        }
        Result.failure(Exception("All Piped search instances failed"))
    }

    /**
     * 動画再生ストリーム情報の取得（高速フォールバック）
     */
    suspend fun extractStreamInfo(videoId: String): Result<StreamInfoData> = withContext(Dispatchers.IO) {
        for (baseUrl in INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/streams/$videoId")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val jsonElement = try {
                        gson.fromJson(body, com.google.gson.JsonElement::class.java)
                    } catch (e: Exception) {
                        return@use
                    }
                    if (!jsonElement.isJsonObject) return@use
                    val json = jsonElement.asJsonObject

                    val title = json.getStringOrNull("title") ?: "Video"
                    val uploader = json.getStringOrNull("uploader") ?: "Channel"
                    val duration = json.getLongOrDefault("duration", 0L)
                    val hlsUrl = json.getStringOrNull("hls")
                    val dashUrl = json.getStringOrNull("dash")

                    val videoStreams = mutableListOf<VideoStream>()
                    json.getAsJsonArray("videoStreams")?.forEach { elem ->
                        if (elem.isJsonObject) {
                            val obj = elem.asJsonObject
                            val url = obj.getStringOrNull("url") ?: ""
                            val quality = obj.getStringOrNull("quality")
                                ?: obj.getStringOrNull("resolution") ?: "720p"
                            val format = obj.getStringOrNull("format") ?: "mp4"
                            val videoOnly = obj.getBooleanOrDefault("videoOnly", false)
                            val bitrate = obj.getIntOrDefault("bitrate", 0)
                            if (url.isNotEmpty()) {
                                videoStreams.add(
                                    VideoStream(
                                        url = url,
                                        resolution = quality,
                                        format = format,
                                        isVideoOnly = videoOnly,
                                        bitrate = bitrate
                                    )
                                )
                            }
                        }
                    }

                    val audioStreams = mutableListOf<AudioStream>()
                    json.getAsJsonArray("audioStreams")?.forEach { elem ->
                        if (elem.isJsonObject) {
                            val obj = elem.asJsonObject
                            val url = obj.getStringOrNull("url") ?: ""
                            val format = obj.getStringOrNull("format") ?: "m4a"
                            val bitrate = obj.getIntOrDefault("bitrate", 0)
                            if (url.isNotEmpty()) {
                                audioStreams.add(AudioStream(url = url, format = format, bitrate = bitrate))
                            }
                        }
                    }

                    if (videoStreams.isNotEmpty() || hlsUrl != null) {
                        Log.i(TAG, "Stream info extracted successfully from Piped: $baseUrl (${videoStreams.size} streams)")
                        return@withContext Result.success(
                            StreamInfoData(
                                videoId = videoId,
                                title = title,
                                uploaderName = uploader,
                                videoStreams = videoStreams,
                                audioStreams = audioStreams,
                                hlsUrl = hlsUrl,
                                dashUrl = dashUrl,
                                durationSeconds = duration
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped stream info on $baseUrl failed: ${e.message}")
            }
        }
        Result.failure(Exception("All Piped instances failed to extract streams for: $videoId"))
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val elem = get(key) ?: return null
        return if (!elem.isJsonNull) elem.asString else null
    }

    private fun JsonObject.getLongOrDefault(key: String, default: Long = 0L): Long {
        val elem = get(key) ?: return default
        return if (!elem.isJsonNull) elem.asLong else default
    }

    private fun JsonObject.getIntOrDefault(key: String, default: Int = 0): Int {
        val elem = get(key) ?: return default
        return if (!elem.isJsonNull) elem.asInt else default
    }

    private fun JsonObject.getBooleanOrDefault(key: String, default: Boolean = false): Boolean {
        val elem = get(key) ?: return default
        return if (!elem.isJsonNull) elem.asBoolean else default
    }
}

