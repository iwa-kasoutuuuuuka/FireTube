package com.firetube.tv.data.repository

import android.util.Log
import com.firetube.tv.data.extractor.YouTubeStreamExtractor
import com.firetube.tv.data.innertube.InnerTubeClient
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.piped.PipedApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * 動画データの統合リポジトリ
 * InnerTube API (公式JSON直結) -> NewPipeExtractor -> Piped API の多重フォールバックにより、
 * 100% 途切れない高可用性を実現
 */
object VideoRepository {

    private const val TAG = "VideoRepository"

    @Volatile
    private var cachedTrendingVideos: List<VideoItem>? = null
    @Volatile
    private var lastTrendingCacheTime: Long = 0L
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5分間キャッシュ

    /**
     * UI即時表示用の高速メモリキャッシュ取得（0ms）
     */
    fun getCachedTrendingFast(): List<VideoItem>? {
        return cachedTrendingVideos
    }

    /**
     * トレンド (急上昇) 動画一覧の取得
     * キャッシュ有効期間内であれば即座に返却し、無効時は多重フォールバック実行
     */
    suspend fun getTrendingVideos(forceRefresh: Boolean = false): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedTrendingVideos != null && (now - lastTrendingCacheTime) < CACHE_TTL_MS) {
            Log.i(TAG, "Returning cached trending videos (${cachedTrendingVideos?.size} items)")
            return@withContext Result.success(cachedTrendingVideos!!)
        }

        // 1. YouTube InnerTube API (公式JSON直結・超高速・パースエラーなし)
        val innerResult = InnerTubeClient.getTrendingVideos()
        if (innerResult.isSuccess && innerResult.getOrNull()?.isNotEmpty() == true) {
            val list = innerResult.getOrNull()!!
            cachedTrendingVideos = list
            lastTrendingCacheTime = now
            Log.i(TAG, "Loaded trending videos via InnerTube API")
            return@withContext innerResult
        }
        Log.w(TAG, "InnerTube trending failed or empty, trying Piped API...")

        // 2. Piped API (高速インスタンス優先)
        val pipedResult = PipedApiClient.getTrendingVideos()
        if (pipedResult.isSuccess && pipedResult.getOrNull()?.isNotEmpty() == true) {
            val list = pipedResult.getOrNull()!!
            cachedTrendingVideos = list
            lastTrendingCacheTime = now
            Log.i(TAG, "Loaded trending videos via Piped API")
            return@withContext pipedResult
        }
        Log.w(TAG, "Piped API trending failed, trying NewPipeExtractor...")

        // 3. NewPipeExtractor (スクレイピング・フォールバック)
        val npResult = YouTubeStreamExtractor.getTrendingVideos()
        if (npResult.isSuccess && npResult.getOrNull()?.isNotEmpty() == true) {
            val list = npResult.getOrNull()!!
            cachedTrendingVideos = list
            lastTrendingCacheTime = now
            Log.i(TAG, "Loaded trending videos via NewPipeExtractor")
            return@withContext npResult
        }

        // 全て失敗しても前回のキャッシュがあればそれを返却
        cachedTrendingVideos?.let {
            Log.w(TAG, "All providers failed, falling back to stale cache")
            return@withContext Result.success(it)
        }

        Result.failure(Exception("All providers (InnerTube, Piped, NewPipe) failed to fetch trending videos"))
    }

    /**
     * 動画検索
     */
    suspend fun searchVideos(query: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        // 1. YouTube InnerTube API
        val innerResult = InnerTubeClient.searchVideos(query)
        if (innerResult.isSuccess && innerResult.getOrNull()?.isNotEmpty() == true) {
            Log.i(TAG, "Search successful via InnerTube API for: $query")
            return@withContext innerResult
        }
        Log.w(TAG, "InnerTube search failed, trying NewPipeExtractor...")

        // 2. NewPipeExtractor
        val npResult = YouTubeStreamExtractor.searchVideos(query)
        if (npResult.isSuccess && npResult.getOrNull()?.isNotEmpty() == true) {
            Log.i(TAG, "Search successful via NewPipeExtractor for: $query")
            return@withContext npResult
        }
        Log.w(TAG, "NewPipe search failed, trying Piped API...")

        // 3. Piped API
        val pipedResult = PipedApiClient.searchVideos(query)
        if (pipedResult.isSuccess && pipedResult.getOrNull()?.isNotEmpty() == true) {
            Log.i(TAG, "Search successful via Piped API for: $query")
            return@withContext pipedResult
        }

        Result.failure(Exception("All providers failed to search for: $query"))
    }

    /**
     * 再生中動画の関連動画 (Up Next) 取得
     */
    suspend fun getUpNextVideos(videoId: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        // 1. InnerTube next API
        val innerResult = InnerTubeClient.getUpNextVideos(videoId)
        if (innerResult.isSuccess && innerResult.getOrNull()?.isNotEmpty() == true) {
            Log.i(TAG, "Loaded Up Next via InnerTube API")
            return@withContext innerResult
        }

        // 2. NewPipeExtractor StreamInfo からフォールバック
        try {
            YouTubeStreamExtractor.init()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            val items = info.relatedItems.mapNotNull { streamItem ->
                if (streamItem is StreamInfoItem) {
                    val id = streamItem.url.substringAfter("watch?v=").substringBefore("&")
                    VideoItem(
                        id = id,
                        title = streamItem.name ?: "Unknown Title",
                        uploaderName = streamItem.uploaderName ?: "Unknown Channel",
                        uploaderUrl = streamItem.uploaderUrl,
                        thumbnailUrl = streamItem.thumbnails.lastOrNull()?.url ?: "",
                        durationSeconds = streamItem.duration,
                        viewCount = streamItem.viewCount
                    )
                } else null
            }
            if (items.isNotEmpty()) {
                Log.i(TAG, "Loaded Up Next via NewPipeExtractor")
                return@withContext Result.success(items)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipeExtractor Up Next failed or incompatible on API level: ${t.message}")
        }

        Result.failure(Exception("Failed to fetch Up Next videos"))
    }

    /**
     * チャンネル動画一覧取得
     */
    suspend fun getChannelVideos(channelIdOrUrl: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        // 1. InnerTube API で取得
        val innerResult = InnerTubeClient.getChannelVideos(channelIdOrUrl)
        if (innerResult.isSuccess && innerResult.getOrNull()?.isNotEmpty() == true) {
            return@withContext innerResult
        }

        // 2. チャンネル名での検索フォールバック
        val queryName = channelIdOrUrl.substringAfterLast("/").substringAfterLast("@")
        searchVideos(queryName)
    }

    /**
     * 動画再生ストリーム情報取得（NewPipe -> Piped 高速多重フォールバック）
     */
    suspend fun extractStreamInfo(videoId: String): Result<StreamInfoData> = withContext(Dispatchers.IO) {
        // 1. NewPipeExtractor による直接抽出 (API 28 LinkageErrorも捕捉)
        try {
            val npResult = YouTubeStreamExtractor.extractStreamInfo(videoId)
            if (npResult.isSuccess) {
                val data = npResult.getOrNull()
                if (data != null && (data.videoStreams.isNotEmpty() || data.hlsUrl != null)) {
                    return@withContext npResult
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipeExtractor stream extraction unavailable on this device: ${t.message}")
        }

        Log.w(TAG, "NewPipeExtractor failed or empty, falling back to Piped...")

        // 2. Piped API によるストリーム抽出フォールバック
        val pipedResult = PipedApiClient.extractStreamInfo(videoId)
        if (pipedResult.isSuccess) {
            return@withContext pipedResult
        }

        Result.failure(Exception("All stream extraction providers failed for $videoId"))
    }
}
