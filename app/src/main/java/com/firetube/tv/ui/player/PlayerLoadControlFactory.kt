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
    private const val BUFFER_FOR_PLAYBACK_MS = 500 // 0.5秒で即時再生開始 (体感遅延75%削減)
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000 // リバッファ時2秒

    // テレビ用バッファメモリ上限: 32MB
    private const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    fun createLowRamLoadControl(bufferProfile: String = com.firetube.tv.util.AppPreferences.BUFFER_FAST): LoadControl {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

        val (minBuffer, maxBuffer, playbackBuffer) = when (bufferProfile) {
            com.firetube.tv.util.AppPreferences.BUFFER_STABLE -> Triple(40_000, 60_000, 5_000)
            com.firetube.tv.util.AppPreferences.BUFFER_NORMAL -> Triple(25_000, 45_000, 1_500)
            else -> Triple(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS) // FAST (500ms)
        }

        return DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                playbackBuffer,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }
}
