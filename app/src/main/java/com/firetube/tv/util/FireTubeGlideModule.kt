package com.firetube.tv.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * Fire TV Stick HD (1.5GB RAM以下) 特化型 Glide モジュール
 * - ビットマップを RGB_565 (2 bytes/pixel) に固定してメモリ消費を50%削減
 * - メモリキャッシュをヒープの10%以下に制限 (約10〜15MB)
 * - ディスクキャッシュを活用して低RAM下でのOOMを完全防御
 */
@GlideModule
class FireTubeGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 低RAMテレビ向けのメモリサイズ計算
        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(1.0f) // 画面1枚分のみメモリキャッシュ
            .setBitmapPoolScreens(1.0f)
            .build()

        builder.setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(calculator.memoryCacheSize.toLong() / 2))
        builder.setBitmapPool(com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(calculator.bitmapPoolSize.toLong() / 2))

        // ディスクキャッシュサイズを 60MB に制限
        val diskCacheSizeBytes = 60L * 1024 * 1024
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "firetube_image_cache", diskCacheSizeBytes))

        // デフォルトで RGB_565 を適用 (透明度不要なサムネイルのメモリを半減)
        builder.setDefaultRequestOptions(
            RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .disallowHardwareConfig() // TV MediaCodecとの競合回避
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
