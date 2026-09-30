package com.firetube.tv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 視聴履歴および登録チャンネル等のローカル保存用Entity
 */
@Entity(tableName = "history_videos")
data class VideoHistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val lastPlayedPositionMs: Long = 0,
    val lastPlayedTimestamp: Long = System.currentTimeMillis(),
    val uploaderUrl: String? = null // DB v2 で追加 (履歴から再生した動画でもチャンネル登録を ID で行うため)
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String? = null,
    val subscribedTimestamp: Long = System.currentTimeMillis()
)

/** 「後で見る」リスト (DB v2 で追加) */
@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val addedTimestamp: Long = System.currentTimeMillis()
)
