package com.firetube.tv.data.network

import android.util.Log
import android.util.LruCache
import com.firetube.tv.data.model.DislikeVotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Return YouTube Dislike (RYD) API クライアント
 * 非公式ながら世界中の主要TV/サードパーティクライアントで利用されている
 * パブリック API (https://returnyoutubedislikeapi.com/votes?videoId=...)
 */
object ReturnYouTubeDislikeClient {

    private const val TAG = "RYDClient"
    private const val BASE_URL = "https://returnyoutubedislikeapi.com/votes?videoId="

    // 直近50件の評価をメモリキャッシュ
    private val cache = LruCache<String, DislikeVotes>(50)

    suspend fun getVotes(videoId: String): Result<DislikeVotes> = withContext(Dispatchers.IO) {
        val cached = cache.get(videoId)
        if (cached != null) {
            return@withContext Result.success(cached)
        }

        try {
            val url = "$BASE_URL$videoId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FireTube/1.2 (FireTV; Android Leanback)")
                .get()
                .build()

            val response = NetworkClient.client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("RYD API HTTP ${response.code}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val votes = NetworkClient.gson.fromJson(body, DislikeVotes::class.java)
            if (votes != null) {
                cache.put(videoId, votes)
                Result.success(votes)
            } else {
                Result.failure(Exception("Parse error"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch RYD votes for $videoId: ${e.message}")
            Result.failure(e)
        }
    }
}

