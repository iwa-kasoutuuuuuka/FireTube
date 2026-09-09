package com.firetube.tv.data.model

import java.io.Serializable

/**
 * Return YouTube Dislike (RYD) API レスポンスモデル
 */
data class DislikeVotes(
    val id: String,
    val likes: Long = 0,
    val dislikes: Long = 0,
    val rating: Double = 0.0,
    val viewCount: Long = 0
) : Serializable {

    /**
     * 高評価率パーセンテージ (0% - 100%)
     */
    val likePercentage: Int
        get() {
            val total = likes + dislikes
            return if (total > 0) ((likes.toDouble() / total) * 100).toInt() else 100
        }

    /**
     * 日本語フォーマットの評価テキスト
     * 例: "高評価 1.2万 / 低評価 450 (96%)"
     */
    val formattedSummary: String
        get() {
            val formattedLikes = formatCount(likes)
            val formattedDislikes = formatCount(dislikes)
            return "高評価 $formattedLikes / 低評価 $formattedDislikes ($likePercentage%)"
        }

    companion object {
        fun formatCount(count: Long): String {
            return when {
                count >= 100_000_000 -> String.format("%.1f億", count / 100_000_000.0)
                count >= 10_000 -> String.format("%.1f万", count / 10_000.0)
                count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}

