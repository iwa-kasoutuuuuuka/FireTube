package com.firetube.tv.data.model

import java.io.Serializable

/**
 * 動画アイテム情報 (Leanbackカードおよび一覧表示用)
 */
data class VideoItem(
    val id: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String? = null,
    val thumbnailUrl: String,
    val durationSeconds: Long = 0,
    val viewCount: Long = 0,
    val uploadDate: String? = null
) : Serializable {

    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return ""
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
}
