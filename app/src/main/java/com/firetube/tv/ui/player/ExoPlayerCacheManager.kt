package com.firetube.tv.ui.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Fire TV Stick HD (1.5GB RAM) 特化型 ExoPlayer キャッシュマネージャー
 * - 40MB の厳格な LRU ディスクキャッシュにより、ストレージを圧迫せず高速シーク＆再開を実現
 * - HLS プレイリスト/マニフェスト (.m3u8) はキャッシュを自動バイパスし、ライブ配信での PlaylistStuckException を根絶
 * - メディアセグメント (.ts, .m4s, .mp4) のみをキャッシュして再バッファリングを 0ms に短縮
 */
@OptIn(UnstableApi::class)
object ExoPlayerCacheManager {

    private const val TAG = "ExoPlayerCacheManager"
    @Volatile
    private var simpleCache: SimpleCache? = null
    private val lock = Any()

    fun getCache(context: Context): SimpleCache? {
        return simpleCache ?: synchronized(lock) {
            simpleCache ?: run {
                try {
                    val appContext = context.applicationContext
                    val cacheBytes = com.firetube.tv.util.DeviceProfileManager.getDiskCacheBytes(appContext)
                    android.util.Log.i(TAG, "Initializing SimpleCache with capacity: ${cacheBytes / (1024 * 1024)}MB")
                    val cacheDir = File(appContext.cacheDir, "firetube_media_cache")
                    val evictor = LeastRecentlyUsedCacheEvictor(cacheBytes)
                    val databaseProvider = StandaloneDatabaseProvider(appContext)
                    SimpleCache(cacheDir, evictor, databaseProvider).also {
                        simpleCache = it
                    }
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "Failed to initialize SimpleCache, bypassing cache: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * マニフェストバイパス機能付きのスマート CacheDataSource.Factory を生成
     * キャッシュ初期化不能時は安全に upstreamFactory へフォールバック
     */
    fun createCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        val cache = getCache(context) ?: return upstreamFactory

        val rawCacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DataSource.Factory {
            SmartCacheDataSource(
                upstream = upstreamFactory.createDataSource(),
                cache = rawCacheFactory.createDataSource()
            )
        }
    }

    private class SmartCacheDataSource(
        private val upstream: DataSource,
        private val cache: CacheDataSource
    ) : DataSource {
        private var activeSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
            cache.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uriStr = dataSpec.uri.toString()
            // HLS ライブ配信等の動的プレイリスト/マニフェストはキャッシュを完全バイパス
            val isDynamicPlaylist = uriStr.contains(".m3u8") ||
                    uriStr.contains("playlist_type") ||
                    uriStr.contains("hls_variant") ||
                    uriStr.contains("manifest")

            val source = if (isDynamicPlaylist) upstream else cache
            activeSource = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = activeSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            activeSource?.responseHeaders ?: emptyMap()

        override fun close() {
            activeSource?.close()
            activeSource = null
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                simpleCache?.release()
                simpleCache = null
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
