package com.firetube.tv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.firetube.tv.util.AppPreferences
import com.firetube.tv.util.DeviceProfileManager

/**
 * Fire TV Stick 4K Max & HD アダプティブ ExoPlayer バッファコントローラー
 * - 4K Max (2GB RAM): 80MB 大容量バッファ、250ms 瞬時再生、30秒バックバッファ（巻き戻し待ち0秒）
 * - HD (1.5GB RAM): 32MB 低RAMバッファ、Task Kill ゼロの徹底した安全設計
 */
@OptIn(UnstableApi::class)
object PlayerLoadControlFactory {

    fun createAdaptiveLoadControl(
        context: Context?,
        bufferProfile: String = AppPreferences.BUFFER_FAST
    ): LoadControl {
        val isHigh = context?.let { DeviceProfileManager.isHighPerformance(it) } ?: false
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

        val (minBuffer, maxBuffer, playbackBuffer, rebufferPlayback) = if (isHigh) {
            when (bufferProfile) {
                AppPreferences.BUFFER_STABLE -> Quadruple(50_000, 90_000, 3_000, 2_000)
                AppPreferences.BUFFER_NORMAL -> Quadruple(35_000, 60_000, 1_000, 1_500)
                else -> Quadruple(25_000, 50_000, 250, 1_000) // 4K Max ウルトラ高速 (250ms起動)
            }
        } else {
            when (bufferProfile) {
                AppPreferences.BUFFER_STABLE -> Quadruple(40_000, 60_000, 5_000, 2_000)
                AppPreferences.BUFFER_NORMAL -> Quadruple(25_000, 45_000, 1_500, 2_000)
                else -> Quadruple(15_000, 30_000, 500, 2_000) // HD 標準 (500ms起動)
            }
        }

        val targetBufferBytes = context?.let { DeviceProfileManager.getTargetBufferBytes(it) }
            ?: (32 * 1024 * 1024)

        val backBufferMs = context?.let { DeviceProfileManager.getBackBufferDurationMs(it) } ?: 0

        val builder = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                playbackBuffer,
                rebufferPlayback
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)

        if (backBufferMs > 0) {
            builder.setBackBuffer(backBufferMs, true)
        }

        return builder.build()
    }

    /**
     * 既存コード互換用 (HD安全値)
     */
    fun createLowRamLoadControl(bufferProfile: String = AppPreferences.BUFFER_FAST): LoadControl {
        return createAdaptiveLoadControl(null, bufferProfile)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
