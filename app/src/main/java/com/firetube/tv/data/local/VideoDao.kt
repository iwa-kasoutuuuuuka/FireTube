package com.firetube.tv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    // 履歴関連
    @Query("SELECT * FROM history_videos ORDER BY lastPlayedTimestamp DESC LIMIT 50")
    fun getHistoryVideos(): Flow<List<VideoHistoryEntity>>

    /** 視聴済み位置バー用の全履歴 (trimHistory により最大 HISTORY_MAX_ROWS 件) */
    @Query("SELECT * FROM history_videos")
    suspend fun getAllHistory(): List<VideoHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHistory(video: VideoHistoryEntity)

    /** 履歴テーブルが無制限に肥大化しないよう、古い履歴を削除する */
    @Query("DELETE FROM history_videos WHERE id NOT IN (SELECT id FROM history_videos ORDER BY lastPlayedTimestamp DESC LIMIT 500)")
    suspend fun trimHistory()

    @Query("SELECT lastPlayedPositionMs FROM history_videos WHERE id = :videoId")
    suspend fun getLastPosition(videoId: String): Long?

    @Query("DELETE FROM history_videos WHERE id = :videoId")
    suspend fun deleteHistory(videoId: String)

    @Query("DELETE FROM history_videos")
    suspend fun clearAllHistory()

    // 登録チャンネル関連
    @Query("SELECT * FROM subscriptions ORDER BY subscribedTimestamp DESC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun deleteSubscription(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    suspend fun isSubscribed(channelId: String): Boolean

    // 後で見る
    @Query("SELECT * FROM watch_later ORDER BY addedTimestamp DESC")
    fun getWatchLater(): Flow<List<WatchLaterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchLater(item: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE id = :videoId")
    suspend fun deleteWatchLater(videoId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE id = :videoId)")
    suspend fun isInWatchLater(videoId: String): Boolean
}
