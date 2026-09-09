package com.firetube.tv

import android.app.Application
import android.util.Log
import com.firetube.tv.data.extractor.YouTubeStreamExtractor
import com.firetube.tv.data.local.AppDatabase
import com.firetube.tv.util.MemoryManager

/**
 * FireTube アプリケーション基盤
 * GMS（Google Play Services）を完全排除したスタンドアロン起動とメモリ監視初期化
 */
class FireTubeApp : Application() {

    companion object {
        private const val TAG = "FireTubeApp"
        lateinit var instance: FireTubeApp
            private set
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "Initializing FireTube for Fire OS TV...")

        // 1. 低RAM対策: メモリ監視マネージャーの登録
        MemoryManager.init(this)

        // 2. GMSフリー抽出エンジンの非同期初期化
        YouTubeStreamExtractor.init()

        // 3. ローカルキャスト待受サーバー起動 (ポート8080)
        com.firetube.tv.cast.LocalCastServer.start(this)
    }
}
