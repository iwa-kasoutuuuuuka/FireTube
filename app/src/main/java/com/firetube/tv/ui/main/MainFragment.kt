package com.firetube.tv.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.extractor.YouTubeStreamExtractor
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.ui.player.PlaybackActivity
import com.firetube.tv.ui.search.SearchActivity
import com.firetube.tv.util.MemoryManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Fire TV ホーム画面 (Leanback BrowseSupportFragment)
 * - カテゴリ別行表示（トレンド、音楽、ゲーム、履歴、登録チャンネル）
 * - リモコンの上下でカテゴリ移動、左右で動画スクロール
 */
class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val trendingAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val historyAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val subscriptionsAdapter = ArrayObjectAdapter(VideoCardPresenter())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUIElements()
        setupEventListeners()
        loadData()
    }

    private fun setupUIElements() {
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.primary_red)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary_red)

        // 行アダプター構築
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        val trendingHeader = HeaderItem(0, getString(R.string.menu_trending))
        rowsAdapter.add(ListRow(trendingHeader, trendingAdapter))

        val historyHeader = HeaderItem(1, getString(R.string.menu_history))
        rowsAdapter.add(ListRow(historyHeader, historyAdapter))

        val subscriptionsHeader = HeaderItem(2, getString(R.string.menu_subscriptions))
        rowsAdapter.add(ListRow(subscriptionsHeader, subscriptionsAdapter))

        adapter = rowsAdapter
    }

    private fun setupEventListeners() {
        // 検索ボタン押下
        setOnSearchClickedListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }

        // 動画カード決定ボタン押下 -> 再生画面へ遷移
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_VIDEO_ID, item.id)
                    putExtra(PlaybackActivity.EXTRA_VIDEO_TITLE, item.title)
                    putExtra(PlaybackActivity.EXTRA_UPLOADER_NAME, item.uploaderName)
                    putExtra(PlaybackActivity.EXTRA_THUMBNAIL_URL, item.thumbnailUrl)
                }
                startActivity(intent)
            }
        }
    }

    private fun loadData() {
        // 1. トレンド動画の取得
        viewLifecycleOwner.lifecycleScope.launch {
            val result = YouTubeStreamExtractor.getTrendingVideos()
            result.onSuccess { videos ->
                trendingAdapter.clear()
                trendingAdapter.addAll(0, videos)
            }
        }

        // 2. ローカル履歴の取得 (Room DB)
        viewLifecycleOwner.lifecycleScope.launch {
            val db = (requireActivity().application as FireTubeApp).database
            val historyList = db.videoDao().getHistoryVideos().firstOrNull() ?: emptyList()
            val historyVideos = historyList.map { entity ->
                VideoItem(
                    id = entity.id,
                    title = entity.title,
                    uploaderName = entity.uploaderName,
                    thumbnailUrl = entity.thumbnailUrl,
                    durationSeconds = entity.durationSeconds
                )
            }
            historyAdapter.clear()
            historyAdapter.addAll(0, historyVideos)
        }
    }

    override fun onStop() {
        super.onStop()
        // 画面が隠れたら画像キャッシュを解放してメモリを節約
        MemoryManager.getInstance()?.clearUiCaches()
    }
}
