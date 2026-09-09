package com.firetube.tv.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.bumptech.glide.Glide

/**
 * Fire OS の極小メモリ管理ユーティリティ
 * OSによるアプリ強制終了 (Task Kill) を防ぐため、非表示時やメモリ圧迫時に即時キャッシュを完全解放する
 */
class MemoryManager(private val context: Context) : ComponentCallbacks2 {

    companion object {
        private const val TAG = "MemoryManager"

        @Volatile
        private var instance: MemoryManager? = null

        fun init(context: Context): MemoryManager {
            return instance ?: synchronized(this) {
                instance ?: MemoryManager(context.applicationContext).also {
                    instance = it
                    context.applicationContext.registerComponentCallbacks(it)
                }
            }
        }

        fun getInstance(): MemoryManager? = instance
    }

    override fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory called with level: $level")
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // ホーム画面に戻った、またはスクリーンセーバー移行時: UIリソースを即座に100%解放
                Log.i(TAG, "UI is hidden. Purging image caches to protect background playback.")
                clearUiCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // メモリが極めて危険な状態: メモリキャッシュを完全フラッシュ
                Log.w(TAG, "Critical memory warning from Fire OS! Flushing all memory caches.")
                clearUiCaches()
                System.gc()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        Log.w(TAG, "Low memory condition detected. Clearing Glide cache.")
        clearUiCaches()
    }

    fun clearUiCaches() {
        try {
            Glide.get(context).clearMemory()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Glide memory cache", e)
        }
    }
}
