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

    val isPlayableAndValid: Boolean
        get() {
            if (id.isBlank()) return false
            // 特殊なUIカード（設定、キャスト、再試行、未登録など）は許可
            if (id.startsWith("__")) return true
            val trimmedTitle = title.trim()
            if (trimmedTitle.isEmpty() || trimmedTitle.equals("No title", ignoreCase = true) || trimmedTitle.equals("Unknown Title", ignoreCase = true)) {
                return false
            }
            val lower = trimmedTitle.lowercase()
            val privatePatterns = listOf(
                "非公開動画",
                "非公開の動画",
                "[private video]",
                "private video",
                "[deleted video]",
                "deleted video",
                "削除された動画"
            )
            for (pattern in privatePatterns) {
                if (lower.contains(pattern)) return false
            }
            if (lower == "private" || lower == "非公開") return false
            return true
        }
}

