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

/**
 * Piped API クライアント (セカンダリフォールバック)
 * YouTube公式APIやスクレイピングがブロックされた場合の代替通信路
 */
object PipedApiClient {

    private const val TAG = "PipedApiClient"

    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee",
        "https://piped-api.lunar.icu"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

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
}
