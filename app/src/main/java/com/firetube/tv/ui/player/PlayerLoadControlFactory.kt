package com.firetube.tv.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Fire TV Stick HD (1.5GB RAM) 特化型 ExoPlayer バッファコントローラー
 * メモリ枯渇による強制終了 (Task Kill) を完全に防ぐため、
 * 先読みバッファサイズと最大メモリを厳格に制限する
 */
object PlayerLoadControlFactory {

    private const val MIN_BUFFER_MS = 15_000 // 15秒
    private const val MAX_BUFFER_MS = 30_000 // 30秒
    private const val BUFFER_FOR_PLAYBACK_MS = 2_000 // 2秒で即時再生開始
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000 // リバッファ時4秒

    // テレビ用バッファメモリ上限: 32MB
    private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    fun createLowRamLoadControl(): LoadControl {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

        return DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}
