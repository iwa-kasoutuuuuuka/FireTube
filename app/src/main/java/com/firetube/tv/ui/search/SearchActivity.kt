package com.firetube.tv.ui.search

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * 検索画面ホスト Activity
 * Fire TV Alexa 音声検索 (ACTION_SEARCH) および D-Pad 入力に対応
 */
class SearchActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        if (savedInstanceState == null) {
            val initialQuery = extractQuery(intent)
            val fragment = SearchFragment.newInstance(initialQuery)
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_fragment, fragment, "SEARCH_FRAGMENT")
                .commitNow()
        } else {
            handleSearchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        val query = extractQuery(intent)
        if (!query.isNullOrBlank()) {
            val fragment = supportFragmentManager.findFragmentByTag("SEARCH_FRAGMENT") as? SearchFragment
            fragment?.setQueryAndSearch(query)
        }
    }

    private fun extractQuery(intent: Intent?): String? {
        if (intent == null) return null
        return intent.getStringExtra(SearchManager.QUERY)
            ?: intent.getStringExtra("query")
            ?: intent.data?.getQueryParameter("q")
    }
}
