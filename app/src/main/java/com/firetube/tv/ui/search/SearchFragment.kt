package com.firetube.tv.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.firetube.tv.R
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.ui.main.VideoCardPresenter
import com.firetube.tv.ui.player.PlaybackActivity
import com.firetube.tv.util.AppPreferences
import com.firetube.tv.util.MemoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.core.content.ContextCompat
import androidx.leanback.widget.SearchOrbView

/**
 * Fire TV 検索画面 (Leanback SearchSupportFragment)
 * 物理リモコンのオンスクリーンキーボードおよび音声認識入力に対応
 */
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val resultsAdapter = ArrayObjectAdapter(VideoCardPresenter(onLongPress = { item ->
        com.firetube.tv.ui.common.VideoActionMenu.show(requireContext(), viewLifecycleOwner.lifecycleScope, item)
    }))
    private var searchJob: Job? = null
    private var currentQuery: String? = null

    // 最近の検索 (検索結果が無い間のみ表示)
    private val recentAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val recentRow by lazy { ListRow(HeaderItem(1, getString(R.string.recent_searches)), recentAdapter) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)

        // 10-foot UI: 離れたテレビからでもフォーカスが即座に視認できるよう、黄色ハイライト色を設定
        val primaryRed = ContextCompat.getColor(requireContext(), R.color.primary_red)
        val accentFocus = ContextCompat.getColor(requireContext(), R.color.accent_focus)
        val colors = SearchOrbView.Colors(primaryRed, accentFocus, ContextCompat.getColor(requireContext(), R.color.text_primary))
        setSearchAffordanceColors(colors)

        val header = HeaderItem(0, getString(R.string.menu_search))
        rowsAdapter.add(ListRow(header, resultsAdapter))

        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                if (item.id.startsWith(PREFIX_RECENT)) {
                    setSearchQuery(item.id.removePrefix(PREFIX_RECENT), true)
                } else if (item.id.startsWith(com.firetube.tv.data.innertube.InnerTubeClient.PLAYLIST_ID_PREFIX)) {
                    startActivity(Intent(requireContext(), com.firetube.tv.ui.channel.ChannelActivity::class.java).apply {
                        putExtra(com.firetube.tv.ui.channel.ChannelActivity.EXTRA_PLAYLIST_ID,
                            item.id.removePrefix(com.firetube.tv.data.innertube.InnerTubeClient.PLAYLIST_ID_PREFIX))
                        putExtra(com.firetube.tv.ui.channel.ChannelActivity.EXTRA_CHANNEL_NAME, item.title)
                    })
                } else {
                    startActivity(PlaybackActivity.createIntent(requireContext(), item))
                }
            }
        })
        updateRecentSearchesRow()
    }

    private fun updateRecentSearchesRow() {
        val history = AppPreferences.getInstance(requireContext()).searchHistory
        val rowIndex = rowsAdapter.indexOf(recentRow)
        if (history.isEmpty() || resultsAdapter.size() > 0) {
            if (rowIndex >= 0) rowsAdapter.removeItems(rowIndex, 1)
            return
        }
        recentAdapter.clear()
        recentAdapter.addAll(0, history.map { q ->
            VideoItem(id = "$PREFIX_RECENT$q", title = q, uploaderName = getString(R.string.recent_searches), thumbnailUrl = "")
        })
        if (rowIndex < 0) rowsAdapter.add(recentRow)
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        // デバウンスをかけて軽量に検索
        newQuery?.let { query ->
            if (query.trim().length >= 2) {
                performSearch(query.trim())
            }
        }
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        query?.let {
            if (it.isNotBlank()) {
                AppPreferences.getInstance(requireContext()).addSearchHistory(it)
                performSearch(it.trim())
            }
        }
        return true
    }

    private fun performSearch(query: String) {
        // 入力途中の検索と確定 (Submit) が同じクエリの場合、実行中/表示済みの検索を再実行しない
        if (query == currentQuery) return
        currentQuery = query
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(400) // 連打防止デバウンス
            val result = com.firetube.tv.data.repository.VideoRepository.searchVideos(query)
            result.onSuccess { videos ->
                resultsAdapter.clear()
                resultsAdapter.addAll(0, videos)
                updateRecentSearchesRow()
            }.onFailure {
                // 失敗したクエリは再試行できるようにする
                if (currentQuery == query) currentQuery = null
            }
        }
    }

    companion object {
        private const val ARG_INITIAL_QUERY = "arg_initial_query"
        private const val PREFIX_RECENT = "__recent__:"
        private const val REQUEST_SPEECH = 1001

        fun newInstance(initialQuery: String? = null): SearchFragment {
            return SearchFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_QUERY, initialQuery)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialQuery = arguments?.getString(ARG_INITIAL_QUERY)
        if (!initialQuery.isNullOrBlank()) {
            setSearchQuery(initialQuery, true)
        }

        // 音声認識コールバック（マイクアイコン押下時）
        setSpeechRecognitionCallback {
            try {
                val intent = recognizerIntent
                startActivityForResult(intent, REQUEST_SPEECH)
            } catch (e: Exception) {
                // 音声認識サービスが利用できない端末では無視
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == android.app.Activity.RESULT_OK && data != null) {
            setSearchQuery(data, true)
        }
    }

    fun setQueryAndSearch(query: String) {
        setSearchQuery(query, true)
    }

    override fun onStop() {
        super.onStop()
        MemoryManager.getInstance()?.clearUiCaches()
    }
}
