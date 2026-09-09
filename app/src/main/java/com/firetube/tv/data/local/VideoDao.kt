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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHistory(video: VideoHistoryEntity)

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
}
