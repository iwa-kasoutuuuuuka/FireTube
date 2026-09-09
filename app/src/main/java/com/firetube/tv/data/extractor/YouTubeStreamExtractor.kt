package com.firetube.tv.data.extractor

import android.util.Log
import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.SubtitleTrack
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.model.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * GMS（Google Play Services）を一切介さず、
 * NewPipeExtractor を用いて動画・ストリーム情報を抽出するリポジトリラッパー
 * 広告セグメントは最初から含まれないため、完全な広告フリーをネイティブで実現
 */
object YouTubeStreamExtractor {

    private const val TAG = "YouTubeExtractor"
    private var isInitialized = false

    fun init() {
        if (!isInitialized) {
            try {
                NewPipe.init(OkHttpDownloader.createDefault())
                isInitialized = true
                Log.i(TAG, "NewPipeExtractor initialized successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NewPipeExtractor", e)
            }
        }
    }

    /**
     * トレンド (急上昇) 動画の取得
     */
    suspend fun getTrendingVideos(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        init()
        try {
            val kiosk = KioskInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/feed/trending"
            )
            val items = kiosk.relatedItems.mapNotNull { streamItem ->
                if (streamItem.streamType == StreamType.VIDEO_STREAM) {
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
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending videos", e)
            Result.failure(e)
        }
    }

    /**
     * 検索クエリによる動画一覧の取得
     */
    suspend fun searchVideos(query: String): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        init()
        try {
            val searchInfo = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query)
            )
            val items = searchInfo.relatedItems.mapNotNull { streamItem ->
                if (streamItem is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
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
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching videos for: $query", e)
            Result.failure(e)
        }
    }

    /**
     * 動画の詳細ストリーム（HLS / DASH / MP4 / 音声ストリーム）の取得
     */
    suspend fun extractStreamInfo(videoId: String): Result<StreamInfoData> = withContext(Dispatchers.IO) {
        init()
        try {
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

            // 映像ストリーム抽出 (1080p, 720p, 480p, etc.)
            val videoStreams = mutableListOf<VideoStream>()
            info.videoStreams?.forEach { s ->
                videoStreams.add(
                    VideoStream(
                        url = s.url,
                        resolution = s.resolution ?: "720p",
                        format = s.format?.getName() ?: "mp4",
                        isVideoOnly = s.isVideoOnly,
                        bitrate = s.averageBitrate
                    )
                )
            }
            info.videoOnlyStreams?.forEach { s ->
                videoStreams.add(
                    VideoStream(
                        url = s.url,
                        resolution = s.resolution ?: "1080p",
                        format = s.format?.getName() ?: "mp4",
                        isVideoOnly = true,
                        bitrate = s.averageBitrate
                    )
                )
            }

            // 音声ストリーム抽出
            val audioStreams = info.audioStreams?.map { a ->
                AudioStream(
                    url = a.url,
                    format = a.format?.getName() ?: "m4a",
                    bitrate = a.averageBitrate
                )
            } ?: emptyList()

            // 字幕トラック
            val subtitles = info.subtitles?.map { sub ->
                SubtitleTrack(
                    url = sub.url,
                    languageName = sub.displayLanguageName ?: "Japanese",
                    languageCode = sub.languageTag ?: "ja"
                )
            } ?: emptyList()

            val streamData = StreamInfoData(
                videoId = videoId,
                title = info.name ?: "Video",
                uploaderName = info.uploaderName ?: "Channel",
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                hlsUrl = info.hlsUrl,
                dashUrl = info.dashUrl,
                subtitles = subtitles,
                durationSeconds = info.duration
            )

            Result.success(streamData)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting stream info for: $videoId", e)
            Result.failure(e)
        }
    }
}
