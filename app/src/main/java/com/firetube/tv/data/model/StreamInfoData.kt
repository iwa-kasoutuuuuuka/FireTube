package com.firetube.tv.data.model

import java.io.Serializable

/**
 * 動画再生ストリーム情報
 */
data class VideoStream(
    val url: String,
    val resolution: String, // e.g. "1080p", "720p", "480p"
    val format: String,     // e.g. "mp4", "webm"
    val isVideoOnly: Boolean = false,
    val bitrate: Int = 0
) : Serializable

data class AudioStream(
    val url: String,
    val format: String,
    val bitrate: Int = 0
) : Serializable

data class SubtitleTrack(
    val url: String,
    val languageName: String,
    val languageCode: String
) : Serializable

data class StreamInfoData(
    val videoId: String,
    val title: String,
    val uploaderName: String,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val durationSeconds: Long = 0
) : Serializable
