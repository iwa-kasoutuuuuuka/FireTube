package com.firetube.tv.data.extractor

import android.util.Log
import com.firetube.tv.data.model.SponsorSegment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * SponsorBlock API 連携サービス
 * 有志データベースから動画内の案件、OP/ED、チャンネル登録呼びかけ等のスキップ区間を取得する
 */
object SponsorBlockService {

    private const val TAG = "SponsorBlock"
    private const val BASE_URL = "https://sponsor.ajay.app/api/skipSegments"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun getSkipSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        try {
            val categoriesJson = "[\"sponsor\",\"intro\",\"outro\",\"selfpromo\",\"interaction\"]"
            val url = "$BASE_URL?videoID=$videoId&categories=$categoriesJson"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404はスキップ区間が登録されていない正常ケース
                    if (response.code != 404) {
                        Log.w(TAG, "SponsorBlock response code: ${response.code}")
                    }
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val listType = object : TypeToken<List<SponsorSegment>>() {}.type
                val segments: List<SponsorSegment> = gson.fromJson(body, listType)
                Log.i(TAG, "Retrieved ${segments.size} SponsorBlock segments for $videoId")
                return@withContext segments
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch SponsorBlock segments: ${e.message}")
            emptyList()
        }
    }
}
