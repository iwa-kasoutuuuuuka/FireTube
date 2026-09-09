package com.firetube.tv.ui.main

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * メイン画面ホスト Activity
 * Fire TV 向けに横画面固定 (landscape) かつ D-Pad ナビゲーションを最適化
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
    }
}
