package com.firetube.tv.ui.search

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * 検索画面ホスト Activity
 */
class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment, SearchFragment())
                .commitNow()
        }
    }
}
