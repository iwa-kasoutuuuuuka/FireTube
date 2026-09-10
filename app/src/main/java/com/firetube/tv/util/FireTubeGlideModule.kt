package com.firetube.tv.util

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.firetube.tv.data.network.NetworkClient
import java.io.InputStream

/**
 * Fire TV Stick HD 特化型 Glide モジュール
 * - NetworkClient.client (HTTP/2, Chrome User-Agent, ConnectionPool) と直接統合
 * - ビットマップを RGB_565 (2 bytes/pixel) に固定してメモリ消費を50%削減
 * - ディスクキャッシュを活用して低RAM下でのOOMを防御しつつ高速ロード
 */
@GlideModule
class FireTubeGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // OkHttp統合: Chrome User-Agent & HTTP/2 多重化ソケット再利用でサムネイル超高速取得
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(NetworkClient.client)
        )
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(1.5f)
            .setBitmapPoolScreens(1.5f)
            .build()

        builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))
        builder.setBitmapPool(com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(calculator.bitmapPoolSize.toLong()))

        // ディスクキャッシュサイズを 100MB に設定
        val diskCacheSizeBytes = 100L * 1024 * 1024
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "firetube_image_cache", diskCacheSizeBytes))

        // デフォルトで RGB_565 を適用 (透明度不要なサムネイルのメモリを半減)
        builder.setDefaultRequestOptions(
            RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .disallowHardwareConfig()
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
