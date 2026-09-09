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
import com.firetube.tv.data.extractor.YouTubeStreamExtractor
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.ui.main.VideoCardPresenter
import com.firetube.tv.ui.player.PlaybackActivity
import com.firetube.tv.util.MemoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fire TV 検索画面 (Leanback SearchSupportFragment)
 * 物理リモコンのオンスクリーンキーボードおよび音声認識入力に対応
 */
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val resultsAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)

        val header = HeaderItem(0, getString(R.string.menu_search))
        rowsAdapter.add(ListRow(header, resultsAdapter))

        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_VIDEO_ID, item.id)
                    putExtra(PlaybackActivity.EXTRA_VIDEO_TITLE, item.title)
                    putExtra(PlaybackActivity.EXTRA_UPLOADER_NAME, item.uploaderName)
                    putExtra(PlaybackActivity.EXTRA_THUMBNAIL_URL, item.thumbnailUrl)
                }
                startActivity(intent)
            }
        })
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
                performSearch(it.trim())
            }
        }
        return true
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(400) // 連打防止デバウンス
            val result = com.firetube.tv.data.repository.VideoRepository.searchVideos(query)
            result.onSuccess { videos ->
                resultsAdapter.clear()
                resultsAdapter.addAll(0, videos)
            }
        }
    }

    companion object {
        private const val ARG_INITIAL_QUERY = "arg_initial_query"
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
