package com.firetube.tv.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * デバイス能力・パフォーマンス適応マネージャー
 * - Fire TV Stick 4K Max (RAM 2.0GB, Wi-Fi 6, 4コア 2.0GHz) を自動判定
 * - 低スペック HD / Lite (RAM 1.0〜1.5GB) での OOM を防ぎつつ、
 *   4K Max では 80MB バッファ / 250ms 瞬時再生 / 30秒バックバッファを解放
 */
object DeviceProfileManager {

    enum class PerformanceTier {
        HIGH,      // 4K Max / Cube / 2GB+ Android TV
        STANDARD   // Fire TV Stick HD / Lite / 1.5GB 端末
    }

    // Amazon Fire TV の高性能モデルコード (RAM 2GB 以上の 4K Max / Cube)
    private val HIGH_PERFORMANCE_AMAZON_MODELS = setOf(
        "AFTKA",    // Fire TV Stick 4K Max (Gen 1, 2021)
        "AFTKRT",   // Fire TV Stick 4K Max (Gen 2, 2023, 16GB)
        "AFTKMST",  // Fire TV Stick 4K Max (Variant)
        "AFTMM",    // Fire TV Cube (Gen 2)
        "AFTGAZL"   // Fire TV Cube (Gen 3)
    )

    // 1.75GB 閾値 (2.0GB RAM 搭載機を安全に検知)
    private const val RAM_THRESHOLD_HIGH_PERFORMANCE_BYTES = 1_879_048_192L // 1.75 GB

    /**
     * 現在の端末のパフォーマンスティアを取得
     * ユーザー設定が「自動」の場合はハードウェアスペック（RAM/CPU/機種）から自動判定
     */
    fun getTier(context: Context): PerformanceTier {
        val prefs = AppPreferences.getInstance(context)
        return when (prefs.performanceProfile) {
            AppPreferences.PROFILE_HIGH -> PerformanceTier.HIGH
            AppPreferences.PROFILE_STANDARD -> PerformanceTier.STANDARD
            else -> detectHardwareTier(context)
        }
    }

    /**
     * ハードウェアスペックの自動検出
     */
    fun detectHardwareTier(context: Context): PerformanceTier {
        val model = Build.MODEL ?: ""
        if (HIGH_PERFORMANCE_AMAZON_MODELS.contains(model)) {
            return PerformanceTier.HIGH
        }

        val totalMem = getTotalMemoryBytes(context)
        if (totalMem >= RAM_THRESHOLD_HIGH_PERFORMANCE_BYTES) {
            return PerformanceTier.HIGH
        }

        return PerformanceTier.STANDARD
    }

    fun isHighPerformance(context: Context): Boolean {
        return getTier(context) == PerformanceTier.HIGH
    }

    /**
     * 端末の総物理メモリ (Bytes)
     */
    fun getTotalMemoryBytes(context: Context): Long {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            memInfo.totalMem
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * ExoPlayer メモリバッファ上限
     * - HIGH (4K Max): 80MB (4K 60fps VP9/AV1 の高ビットレートに余裕で対応)
     * - STANDARD (HD): 32MB (1.5GB RAM での Task Kill を完全防止)
     */
    fun getTargetBufferBytes(context: Context): Int {
        return if (isHighPerformance(context)) {
            80 * 1024 * 1024 // 80MB
        } else {
            32 * 1024 * 1024 // 32MB
        }
    }

    /**
     * ExoPlayer ディスクキャッシュ上限
     * - HIGH (4K Max): 120MB (16GB/8GB 高速ストレージを活用)
     * - STANDARD (HD): 40MB
     */
    fun getDiskCacheBytes(context: Context): Long {
        return if (isHighPerformance(context)) {
            120L * 1024 * 1024 // 120MB
        } else {
            40L * 1024 * 1024  // 40MB
        }
    }

    /**
     * ExoPlayer 初期再生開始バッファ (ms)
     * - HIGH (4K Max): 250ms (爆速瞬時再生)
     * - STANDARD (HD): 500ms
     */
    fun getBufferForPlaybackMs(context: Context): Int {
        return if (isHighPerformance(context)) 250 else 500
    }

    /**
     * ExoPlayer リバッファ時再生再開バッファ (ms)
     */
    fun getBufferForPlaybackAfterRebufferMs(context: Context): Int {
        return if (isHighPerformance(context)) 1_000 else 2_000
    }

    /**
     * ExoPlayer 最小先読みバッファ (ms)
     * - HIGH: 25秒 (高ビットレートでも途切れない)
     * - STANDARD: 15秒
     */
    fun getMinBufferMs(context: Context): Int {
        return if (isHighPerformance(context)) 25_000 else 15_000
    }

    /**
     * ExoPlayer 最大先読みバッファ (ms)
     * - HIGH: 50秒
     * - STANDARD: 30秒
     */
    fun getMaxBufferMs(context: Context): Int {
        return if (isHighPerformance(context)) 50_000 else 30_000
    }

    /**
     * ExoPlayer 保持バックバッファ (ms)
     * - HIGH (4K Max): 30秒 (10秒戻るや巻き戻し操作が0ms即座に完了)
     * - STANDARD (HD): 0ms (メモリ節約)
     */
    fun getBackBufferDurationMs(context: Context): Int {
        return if (isHighPerformance(context)) 30_000 else 0
    }

    /**
     * リモコンフォーカス滞在判定時間 (Focus-Dwell)
     * - HIGH (4K Max): 400ms
     * - STANDARD (HD): 700ms
     */
    fun getPrefetchDwellMs(context: Context): Long {
        return if (isHighPerformance(context)) 400L else 700L
    }

    /**
     * 設定画面等で表示するデバイス診断サマリー文字列
     */
    fun getDeviceSummary(context: Context): String {
        val model = Build.MODEL ?: "Android TV"
        val totalMemGb = String.format("%.1f", getTotalMemoryBytes(context) / (1024.0 * 1024.0 * 1024.0))
        val cores = Runtime.getRuntime().availableProcessors()
        val tierDesc = if (isHighPerformance(context)) "4K Ultra" else "標準 (省メモリ)"
        return "$model (${totalMemGb}GB RAM / ${cores}コア) - $tierDesc"
    }
}
