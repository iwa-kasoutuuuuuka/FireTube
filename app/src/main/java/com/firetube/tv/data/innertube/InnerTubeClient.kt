package com.firetube.tv.data.innertube

import android.util.Log
import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.SubtitleTrack
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

    private const val IOS_USER_AGENT = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; ja_JP)"
    private const val IOS_KIDS_USER_AGENT = "com.google.ios.youtubekids/9.01.0 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; ja_JP)"
    private const val ANDROID_KIDS_USER_AGENT = "com.google.android.apps.youtube.kids/9.01.0 (Linux; U; Android 11)"
    private const val VISIONOS_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    @Volatile
    private var cachedVisitorData: String? = null
    @Volatile
    private var cachedSignatureTimestamp: Int = 20711

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

    private fun buildIosContext(): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("hl", "ja")
            addProperty("gl", "JP")
            addProperty("clientName", "IOS")
            addProperty("clientVersion", "21.03.2")
            addProperty("clientScreen", "WATCH")
            addProperty("platform", "MOBILE")
            addProperty("deviceMake", "Apple")
            addProperty("deviceModel", "iPhone16,2")
            addProperty("osName", "iOS")
            addProperty("osVersion", "18.7.2.22H124")
            addProperty("utcOffsetMinutes", 540)
        }
        return JsonObject().apply {
            add("client", clientObj)
        }
    }

    private fun buildIosKidsContext(): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("hl", "ja")
            addProperty("gl", "JP")
            addProperty("clientName", "IOS_KIDS")
            addProperty("clientVersion", "9.01.0")
            addProperty("clientScreen", "WATCH")
            addProperty("platform", "MOBILE")
            addProperty("deviceMake", "Apple")
            addProperty("deviceModel", "iPhone16,2")
            addProperty("osName", "iOS")
            addProperty("osVersion", "18.7.2.22H124")
            addProperty("utcOffsetMinutes", 540)
        }
        return JsonObject().apply {
            add("client", clientObj)
        }
    }

    private fun buildAndroidKidsContext(): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("hl", "ja")
            addProperty("gl", "JP")
            addProperty("clientName", "ANDROID_KIDS")
            addProperty("clientVersion", "9.01.0")
            addProperty("androidSdkVersion", 30)
            addProperty("osName", "Android")
            addProperty("osVersion", "11")
            addProperty("platform", "MOBILE")
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

    private fun findVideoRenderersRecursive(element: JsonObject, result: MutableList<VideoItem>, depth: Int = 0) {
        if (depth > 15) return
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
                findVideoRenderersRecursive(value.asJsonObject, result, depth + 1)
            } else if (value.isJsonArray) {
                for (subElem in value.asJsonArray) {
                    if (subElem.isJsonObject) {
                        findVideoRenderersRecursive(subElem.asJsonObject, result, depth + 1)
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

    /**
     * 動画再生ストリーム情報の取得
     * 1. IOS_KIDS コンテキスト（Made for Kids 動画用 HLS アダプティブマニフェスト最優先）
     * 2. VISIONOS コンテキスト（Made for Kids 対象外の一般動画用 HLS アダプティブマニフェスト最優先）
     * 3. IOS_EMBEDDED コンテキスト（音楽PV・公式チャンネル等、VISIONOSでLOGIN_REQUIREDになる動画の救済フォールバック）
     * 4. ANDROID_KIDS コンテキスト（単一 Muxed MP4 ストリーム取得）
     * 5. IOS コンテキスト（PoToken不要の DASH フォールバック）
     */
    suspend fun extractStreamInfo(videoId: String): Result<StreamInfoData> = withContext(Dispatchers.IO) {
        // 1. IOS_KIDS (Made for Kids 動画の HLS 最優先)
        val kidsResult = fetchPlayerStream(videoId, buildIosKidsContext(), IOS_KIDS_USER_AGENT)
        if (kidsResult.isSuccess) {
            val data = kidsResult.getOrNull()
            if (data != null && (data.hlsUrl != null || data.videoStreams.isNotEmpty())) {
                Log.i(TAG, "Successfully extracted stream via IOS_KIDS context for $videoId (hls=${data.hlsUrl != null})")
                return@withContext kidsResult
            }
        }

        // 2. VISIONOS (Made for Kids 対象外の一般動画用 HLS 最優先)
        val visionOsResult = fetchVisionOsStream(videoId)
        if (visionOsResult.isSuccess) {
            val data = visionOsResult.getOrNull()
            if (data != null && (data.hlsUrl != null || data.videoStreams.isNotEmpty())) {
                Log.i(TAG, "Successfully extracted stream via VISIONOS context for $videoId (hls=${data.hlsUrl != null})")
                return@withContext visionOsResult
            }
        }

        // 3. IOS_EMBEDDED (音楽PV・公式チャンネル等、VISIONOSでLOGIN_REQUIREDになる動画用埋め込みコンテキスト)
        val iosEmbedResult = fetchPlayerStream(videoId, buildIosContext(), IOS_USER_AGENT, isEmbedded = true)
        if (iosEmbedResult.isSuccess) {
            val data = iosEmbedResult.getOrNull()
            if (data != null && (data.hlsUrl != null || data.videoStreams.isNotEmpty())) {
                Log.i(TAG, "Successfully extracted stream via IOS_EMBEDDED context for $videoId (${data.videoStreams.size} video, ${data.audioStreams.size} audio)")
                return@withContext iosEmbedResult
            }
        }

        // 4. ANDROID_KIDS (Muxed MP4)
        val androidKidsResult = fetchPlayerStream(videoId, buildAndroidKidsContext(), ANDROID_KIDS_USER_AGENT)
        if (androidKidsResult.isSuccess) {
            val data = androidKidsResult.getOrNull()
            if (data != null && (data.hlsUrl != null || data.videoStreams.isNotEmpty())) {
                Log.i(TAG, "Successfully extracted stream via ANDROID_KIDS context for $videoId")
                return@withContext androidKidsResult
            }
        }

        // 5. IOS (標準フォールバック)
        val iosResult = fetchPlayerStream(videoId, buildIosContext(), IOS_USER_AGENT)
        if (iosResult.isSuccess) {
            Log.i(TAG, "Extracted stream via standard IOS context for $videoId")
            return@withContext iosResult
        }

        Log.e(TAG, "All InnerTube player contexts failed for $videoId")
        kidsResult
    }

    private fun fetchVisitorDataIfNeeded(videoId: String) {
        if (cachedVisitorData != null) return
        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return
                val html = response.body?.string() ?: return
                val visitorMatch = Regex("""["']visitorData["']\s*:\s*["']([^"']+)["']""").find(html)
                if (visitorMatch != null) {
                    cachedVisitorData = visitorMatch.groupValues[1]
                }
                val stsMatch = Regex("""["']signatureTimestamp["']\s*:\s*(\d+)""").find(html)
                if (stsMatch != null) {
                    cachedSignatureTimestamp = stsMatch.groupValues[1].toIntOrNull() ?: 20711
                }
                Log.d(TAG, "Fetched visitorData: ${cachedVisitorData?.take(30)}..., sts=$cachedSignatureTimestamp")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "fetchVisitorDataIfNeeded failed: ${e.message}")
        }
    }

    private fun fetchVisionOsStream(videoId: String): Result<StreamInfoData> {
        return try {
            fetchVisitorDataIfNeeded(videoId)
            val vData = cachedVisitorData
            val sts = cachedSignatureTimestamp

            val clientObj = JsonObject().apply {
                addProperty("hl", "ja")
                addProperty("gl", "JP")
                addProperty("clientName", "VISIONOS")
                addProperty("clientVersion", "1.02")
                addProperty("deviceMake", "Apple")
                addProperty("deviceModel", "RealityDevice17,1")
                addProperty("userAgent", VISIONOS_USER_AGENT)
                addProperty("osName", "visionOS")
                addProperty("osVersion", "26.5.23O471")
                addProperty("utcOffsetMinutes", 540)
                if (vData != null) {
                    addProperty("visitorData", vData)
                }
            }

            val playbackContext = JsonObject().apply {
                val contentPlaybackContext = JsonObject().apply {
                    addProperty("html5Preference", "HTML5_PREF_WANTS")
                    addProperty("signatureTimestamp", sts)
                }
                add("contentPlaybackContext", contentPlaybackContext)
            }

            val payload = JsonObject().apply {
                add("context", JsonObject().apply { add("client", clientObj) })
                addProperty("videoId", videoId)
                add("playbackContext", playbackContext)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("$BASE_URL/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
                .header("X-Youtube-Client-Name", "101")
                .header("X-Youtube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com")
                .header("User-Agent", VISIONOS_USER_AGENT)

            if (vData != null) {
                reqBuilder.header("X-Goog-Visitor-Id", vData)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("VisionOS player HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return Result.failure(Exception("Empty VisionOS response"))
                val json = gson.fromJson(body, JsonObject::class.java)
                parseStreamInfoFromJson(json, videoId)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "fetchVisionOsStream failed for $videoId", e)
            Result.failure(e)
        }
    }

    private fun fetchPlayerStream(
        videoId: String,
        contextJson: JsonObject,
        userAgent: String,
        isEmbedded: Boolean = false
    ): Result<StreamInfoData> {
        return try {
            val payload = JsonObject().apply {
                add("context", contextJson)
                addProperty("videoId", videoId)
                if (isEmbedded) {
                    val playbackContext = JsonObject().apply {
                        val contentPlaybackContext = JsonObject().apply {
                            addProperty("html5Preference", "HTML5_PREF_WANTS")
                            addProperty("signatureTimestamp", cachedSignatureTimestamp)
                        }
                        add("contentPlaybackContext", contentPlaybackContext)
                    }
                    add("playbackContext", playbackContext)
                    val thirdParty = JsonObject().apply {
                        addProperty("embedUrl", "https://www.google.com")
                    }
                    add("thirdParty", thirdParty)
                    addProperty("contentCheckOk", true)
                    addProperty("racyCheckOk", true)
                }
            }

            val request = Request.Builder()
                .url("$BASE_URL/player")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("InnerTube player HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return Result.failure(Exception("Empty player response"))
                val json = gson.fromJson(body, JsonObject::class.java)
                parseStreamInfoFromJson(json, videoId)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "InnerTube fetchPlayerStream failed for $videoId", e)
            Result.failure(e)
        }
    }

    private fun parseStreamInfoFromJson(json: JsonObject, videoId: String): Result<StreamInfoData> {
        val playabilityStatus = json.getAsJsonObject("playabilityStatus")
        val status = playabilityStatus?.get("status")?.asString
        if (status != "OK") {
            val reason = playabilityStatus?.get("reason")?.asString ?: "Status: $status"
            return Result.failure(Exception("InnerTube unplayable: $status - $reason"))
        }

        val streamingData = json.getAsJsonObject("streamingData")
            ?: return Result.failure(Exception("No streamingData in player response for $videoId"))

        val videoStreams = mutableListOf<VideoStream>()
        val audioStreams = mutableListOf<AudioStream>()

        // 1. Muxed formats (音声＋映像統合ストリーム)
        streamingData.getAsJsonArray("formats")?.forEach { elem ->
            if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                val url = obj.get("url")?.asString ?: ""
                if (url.isNotEmpty()) {
                    val quality = obj.get("qualityLabel")?.asString ?: "360p"
                    val mimeType = obj.get("mimeType")?.asString ?: ""
                    val format = if (mimeType.contains("mp4", ignoreCase = true)) "mp4" else "webm"
                    val bitrate = obj.get("bitrate")?.asInt ?: 0
                    videoStreams.add(
                        VideoStream(
                            url = url,
                            resolution = quality,
                            format = format,
                            isVideoOnly = false,
                            bitrate = bitrate
                        )
                    )
                }
            }
        }

        // 2. Adaptive formats (高画質映像・音声セパレートストリーム)
        streamingData.getAsJsonArray("adaptiveFormats")?.forEach { elem ->
            if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                val url = obj.get("url")?.asString ?: ""
                if (url.isNotEmpty()) {
                    val mimeType = obj.get("mimeType")?.asString ?: ""
                    val bitrate = obj.get("bitrate")?.asInt ?: 0
                    if (mimeType.startsWith("video/", ignoreCase = true)) {
                        val quality = obj.get("qualityLabel")?.asString ?: "720p"
                        val format = if (mimeType.contains("mp4", ignoreCase = true)) "mp4" else "webm"
                        videoStreams.add(
                            VideoStream(
                                url = url,
                                resolution = quality,
                                format = format,
                                isVideoOnly = true,
                                bitrate = bitrate
                            )
                        )
                    } else if (mimeType.startsWith("audio/", ignoreCase = true)) {
                        val format = if (mimeType.contains("mp4", ignoreCase = true)) "m4a" else "webm"
                        audioStreams.add(
                            AudioStream(
                                url = url,
                                format = format,
                                bitrate = bitrate
                            )
                        )
                    }
                }
            }
        }

        val hlsUrl = streamingData.get("hlsManifestUrl")?.asString

        if (videoStreams.isEmpty() && hlsUrl == null) {
            return Result.failure(Exception("No playable streams found via InnerTube for $videoId"))
        }

        // 字幕トラックの抽出
        val subtitles = mutableListOf<SubtitleTrack>()
        val captionsObj = json.getAsJsonObject("captions")
        val tracklist = captionsObj?.getAsJsonObject("playerCaptionsTracklistRenderer")
        tracklist?.getAsJsonArray("captionTracks")?.forEach { trackElem ->
            if (trackElem.isJsonObject) {
                val trackObj = trackElem.asJsonObject
                val url = trackObj.get("baseUrl")?.asString ?: ""
                val langCode = trackObj.get("languageCode")?.asString ?: "ja"
                val nameObj = trackObj.getAsJsonObject("name")
                val langName = nameObj?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
                    ?: nameObj?.get("simpleText")?.asString
                    ?: langCode
                if (url.isNotEmpty()) {
                    subtitles.add(SubtitleTrack(url = url, languageName = langName, languageCode = langCode))
                }
            }
        }

        val videoDetails = json.getAsJsonObject("videoDetails")
        val title = videoDetails?.get("title")?.asString ?: "Video"
        val author = videoDetails?.get("author")?.asString ?: "Channel"
        val lengthSeconds = videoDetails?.get("lengthSeconds")?.asString?.toLongOrNull() ?: 0L

        val streamData = StreamInfoData(
            videoId = videoId,
            title = title,
            uploaderName = author,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            hlsUrl = hlsUrl,
            dashUrl = null,
            subtitles = subtitles,
            durationSeconds = lengthSeconds
        )

        Log.i(TAG, "Parsed stream data for $videoId: hls=${hlsUrl != null}, videoStreams=${videoStreams.size}, audioStreams=${audioStreams.size}")
        return Result.success(streamData)
    }
}
