package com.firetube.tv.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.firetube.tv.cast.CastActivity
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.repository.VideoRepository
import com.firetube.tv.ui.channel.ChannelActivity
import com.firetube.tv.ui.player.PlaybackActivity
import com.firetube.tv.ui.search.SearchActivity
import com.firetube.tv.ui.settings.SettingsActivity
import com.firetube.tv.util.MemoryManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Fire TV ホーム画面 (Leanback BrowseSupportFragment)
 * - カテゴリ別行表示（トレンド、履歴、ツール＆設定）
 * - InnerTube / NewPipe / Piped 多重フォールバックによる高可用性
 * - エラー時の再試行（Retry）サポート
 * - 設定画面・スマホキャスト画面へのスムーズな導線
 * - リモコンの上下でカテゴリ移動、左右で動画スクロール
 */
class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private val trendingAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val historyAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val toolsAdapter = ArrayObjectAdapter(VideoCardPresenter())

    companion object {
        const val ID_SETTINGS = "__settings__"
        const val ID_CAST = "__cast__"
        const val ID_RETRY = "__retry__"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUIElements()
        setupEventListeners()
        setupToolsRow()
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

        val toolsHeader = HeaderItem(2, "設定 & 便利機能")
        rowsAdapter.add(ListRow(toolsHeader, toolsAdapter))

        adapter = rowsAdapter
    }

    private fun setupToolsRow() {
        toolsAdapter.clear()
        toolsAdapter.add(
            VideoItem(
                id = ID_SETTINGS,
                title = getString(R.string.menu_settings),
                uploaderName = "画質・倍速・SponsorBlock設定",
                thumbnailUrl = ""
            )
        )
        toolsAdapter.add(
            VideoItem(
                id = ID_CAST,
                title = getString(R.string.menu_cast),
                uploaderName = "同一Wi-Fiのスマホから動画共有",
                thumbnailUrl = ""
            )
        )
    }

    private fun setupEventListeners() {
        // 検索ボタン押下
        setOnSearchClickedListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }

        // カード決定ボタン押下
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                when (item.id) {
                    ID_SETTINGS -> {
                        startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    }
                    ID_CAST -> {
                        startActivity(Intent(requireContext(), CastActivity::class.java))
                    }
                    ID_RETRY -> {
                        loadData()
                    }
                    else -> {
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
        }
    }

    private fun loadData() {
        // 1. トレンド動画の取得 (InnerTube -> NewPipe -> Piped 自動フォールバック)
        progressBarManager.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VideoRepository.getTrendingVideos()
            progressBarManager.hide()

            result.onSuccess { videos ->
                trendingAdapter.clear()
                trendingAdapter.addAll(0, videos)
            }.onFailure {
                trendingAdapter.clear()
                // 再試行カードを追加
                trendingAdapter.add(
                    VideoItem(
                        id = ID_RETRY,
                        title = getString(R.string.network_error_msg),
                        uploaderName = getString(R.string.press_to_retry),
                        thumbnailUrl = ""
                    )
                )
                Toast.makeText(requireContext(), R.string.network_error_msg, Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        // 設定や再生から戻った際に履歴を更新
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
        MemoryManager.getInstance()?.clearUiCaches()
    }
}
