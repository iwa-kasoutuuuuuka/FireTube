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
    private val subscriptionsAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val historyAdapter = ArrayObjectAdapter(VideoCardPresenter())
    private val toolsAdapter = ArrayObjectAdapter(VideoCardPresenter())

    companion object {
        const val ID_SETTINGS = "__settings__"
        const val ID_CAST = "__cast__"
        const val ID_RETRY = "__retry__"
        const val ID_NO_SUB = "__no_sub__"
        const val PREFIX_CHANNEL = "__chan__:"
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

        val cardPresenter = VideoCardPresenter()
        // 行アダプター構築 (影計算バイパス & リサイクルプール拡張で60fpsスクロール)
        val listRowPresenter = ListRowPresenter().apply {
            shadowEnabled = false // 低スペックTV GPUの影計算負荷を根絶
            selectEffectEnabled = false
            setRecycledPoolSize(cardPresenter, 24)
        }
        rowsAdapter = ArrayObjectAdapter(listRowPresenter)

        val trendingHeader = HeaderItem(0, getString(R.string.menu_trending))
        rowsAdapter.add(ListRow(trendingHeader, trendingAdapter))

        val subHeader = HeaderItem(1, getString(R.string.menu_subscriptions))
        rowsAdapter.add(ListRow(subHeader, subscriptionsAdapter))

        val historyHeader = HeaderItem(2, getString(R.string.menu_history))
        rowsAdapter.add(ListRow(historyHeader, historyAdapter))

        val toolsHeader = HeaderItem(3, "設定 & 便利機能")
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
                when {
                    item.id == ID_SETTINGS -> {
                        startActivity(Intent(requireContext(), SettingsActivity::class.java))
                    }
                    item.id == ID_CAST -> {
                        startActivity(Intent(requireContext(), CastActivity::class.java))
                    }
                    item.id == ID_RETRY -> {
                        loadData()
                    }
                    item.id == ID_NO_SUB -> {
                        Toast.makeText(requireContext(), R.string.no_subscriptions_desc, Toast.LENGTH_SHORT).show()
                    }
                    item.id.startsWith(PREFIX_CHANNEL) -> {
                        val channelId = item.id.removePrefix(PREFIX_CHANNEL)
                        val intent = Intent(requireContext(), ChannelActivity::class.java).apply {
                            putExtra(ChannelActivity.EXTRA_CHANNEL_URL, channelId)
                            putExtra(ChannelActivity.EXTRA_CHANNEL_NAME, item.title)
                        }
                        startActivity(intent)
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
        // 1. 高速メモリキャッシュがあれば即時描画（体感待機時間 0ms）
        val cached = VideoRepository.getCachedTrendingFast()
        if (cached != null && cached.isNotEmpty()) {
            trendingAdapter.clear()
            trendingAdapter.addAll(0, cached)
        } else {
            progressBarManager.show()
        }

        // 2. トレンド動画の取得 (キャッシュ期限切れまたは初回時に通信・更新)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VideoRepository.getTrendingVideos(forceRefresh = (cached == null))
            progressBarManager.hide()

            result.onSuccess { videos ->
                trendingAdapter.clear()
                trendingAdapter.addAll(0, videos)
            }.onFailure {
                if (trendingAdapter.size() == 0) {
                    trendingAdapter.clear()
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
        }

        // 3. ローカル登録チャンネルの取得
        loadSubscriptions()

        // 4. ローカル履歴の取得 (Room DB)
        loadHistory()
    }

    private fun loadSubscriptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = (requireActivity().application as FireTubeApp).database
            val subs = db.videoDao().getAllSubscriptions().firstOrNull() ?: emptyList()
            subscriptionsAdapter.clear()
            if (subs.isEmpty()) {
                subscriptionsAdapter.add(
                    VideoItem(
                        id = ID_NO_SUB,
                        title = getString(R.string.no_subscriptions),
                        uploaderName = getString(R.string.no_subscriptions_desc),
                        thumbnailUrl = ""
                    )
                )
            } else {
                val subItems = subs.map { entity ->
                    VideoItem(
                        id = "$PREFIX_CHANNEL${entity.channelId}",
                        title = entity.channelName,
                        uploaderName = "登録チャンネル",
                        thumbnailUrl = entity.channelAvatarUrl ?: ""
                    )
                }
                subscriptionsAdapter.addAll(0, subItems)
            }
        }
    }

    private fun loadHistory() {
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
        // 設定や再生から戻った際に履歴と登録チャンネルを更新
        loadSubscriptions()
        loadHistory()
    }

    override fun onStop() {
        super.onStop()
        MemoryManager.getInstance()?.clearUiCaches()
    }
}
