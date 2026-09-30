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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.cast.CastActivity
import com.firetube.tv.data.innertube.InnerTubeClient
import com.firetube.tv.data.local.WatchProgressStore
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.ui.common.VideoActionMenu
import com.firetube.tv.data.repository.VideoRepository
import com.firetube.tv.ui.channel.ChannelActivity
import com.firetube.tv.ui.player.PlaybackActivity
import com.firetube.tv.ui.search.SearchActivity
import com.firetube.tv.ui.settings.SettingsActivity
import com.firetube.tv.util.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // 全行で 1 つの Presenter を共有 (RecycledViewPool は Presenter 単位のため、共有しないとプール拡張が効かない)
    private val cardPresenter = VideoCardPresenter(onLongPress = { item ->
        // 後で見る・履歴の変更は Room の Flow 経由で自動反映される
        VideoActionMenu.show(requireContext(), viewLifecycleOwner.lifecycleScope, item)
    })
    private val trendingAdapter = ArrayObjectAdapter(cardPresenter)
    private val kidsAdapter = ArrayObjectAdapter(cardPresenter)
    private val subscriptionsAdapter = ArrayObjectAdapter(cardPresenter)
    private val subscriptionFeedAdapter = ArrayObjectAdapter(cardPresenter)
    private val watchLaterAdapter = ArrayObjectAdapter(cardPresenter)
    private val liveAdapter = ArrayObjectAdapter(cardPresenter)
    private val shortsAdapter = ArrayObjectAdapter(cardPresenter)
    private val liveRow by lazy { ListRow(HeaderItem(7, getString(R.string.menu_live)), liveAdapter) }
    private val shortsRow by lazy { ListRow(HeaderItem(8, getString(R.string.menu_shorts)), shortsAdapter) }
    private val watchLaterRow by lazy {
        ListRow(HeaderItem(6, getString(R.string.menu_watch_later)), watchLaterAdapter)
    }
    // 新着行は新着動画がある場合のみ表示する (空の行ヘッダーを出さないため動的に追加/削除)
    private val subscriptionFeedRow by lazy {
        ListRow(HeaderItem(5, getString(R.string.menu_subscription_feed)), subscriptionFeedAdapter)
    }
    private var subscriptionFeedJob: kotlinx.coroutines.Job? = null
    private val historyAdapter = ArrayObjectAdapter(cardPresenter)
    private val toolsAdapter = ArrayObjectAdapter(cardPresenter)

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
        observeLocalData()
    }

    private fun setupUIElements() {
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.primary_red)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.primary_red)

        // 行アダプター構築 (影計算バイパス & リサイクルプール拡張で60fpsスクロール)
        val listRowPresenter = ListRowPresenter().apply {
            shadowEnabled = false // 低スペックTV GPUの影計算負荷を根絶
            selectEffectEnabled = false
            setRecycledPoolSize(cardPresenter, 24)
        }
        rowsAdapter = ArrayObjectAdapter(listRowPresenter)

        val trendingHeader = HeaderItem(0, getString(R.string.menu_trending))
        rowsAdapter.add(ListRow(trendingHeader, trendingAdapter))

        val kidsHeader = HeaderItem(1, getString(R.string.menu_kids))
        rowsAdapter.add(ListRow(kidsHeader, kidsAdapter))

        val subHeader = HeaderItem(2, getString(R.string.menu_subscriptions))
        rowsAdapter.add(ListRow(subHeader, subscriptionsAdapter))

        val historyHeader = HeaderItem(3, getString(R.string.menu_history))
        rowsAdapter.add(ListRow(historyHeader, historyAdapter))

        val toolsHeader = HeaderItem(4, "設定 & 便利機能")
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
                        startActivity(PlaybackActivity.createIntent(requireContext(), item))
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
            preloadThumbnails(cached)
        } else {
            progressBarManager.show()
        }

        // 2. トレンド動画の取得 (キャッシュ期限切れまたは初回時に通信・更新)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VideoRepository.getTrendingVideos(forceRefresh = (cached == null))
            progressBarManager.hide()

            result.onSuccess { videos ->
                // キャッシュ描画済みの内容と同一なら再描画・再プリロードしない
                if (trendingAdapter.unmodifiableList<Any>() != videos) {
                    trendingAdapter.clear()
                    trendingAdapter.addAll(0, videos)
                    preloadThumbnails(videos)
                }
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

        // 3. キッズ＆ファミリー向け人気定番動画の即時表示 (0ms) & バックグラウンド・プリウォーム
        val kidsVideos = VideoRepository.getPopularKidsVideos()
        kidsAdapter.clear()
        kidsAdapter.addAll(0, kidsVideos)
        preloadThumbnails(kidsVideos)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            delay(1500) // ホーム画面の初期描画負荷を避けて低優先度で実行
            // ⑧ VisitorData を先取り: 初回 VISIONOS 抽出時の追加 HTTP ラウンドトリップを 0ms 化
            InnerTubeClient.preFetchVisitorData()
            // ⑤ キッズ動画のストリーム並列先読み
            VideoRepository.prewarmStreamCache(kidsVideos.map { it.id })
        }

        // 4. ライブ配信中 / ショート (キッズ行の直前に、この順で表示)
        viewLifecycleOwner.lifecycleScope.launch {
            setOptionalRow(liveRow, VideoRepository.getLiveVideos(), beforeAdapter = kidsAdapter)
            setOptionalRow(shortsRow, VideoRepository.getShortsVideos(), beforeAdapter = kidsAdapter)
        }

        // 5. 登録チャンネル・後で見る・履歴 (Room DB) は observeLocalData で監視
    }

    /**
     * 内容が変わっていない場合はアダプターを更新しない
     * (clear→addAll は行全体の再バインド・サムネイル再読み込み・フォーカス位置のリセットを引き起こす)
     */
    private fun replaceIfChanged(adapter: ArrayObjectAdapter, items: List<VideoItem>) {
        if (adapter.unmodifiableList<Any>() == items) return
        adapter.clear()
        adapter.addAll(0, items)
    }

    /**
     * 初期表示領域にある上位カード (先頭12件) のサムネイルを事前デコード・キャッシュ (Smart Preload)
     * ② 旧: 6枚 → 新: 12枚 (スクロール初動でのチラつきをさらに削減)
     */
    private fun preloadThumbnails(videos: List<VideoItem>) {
        if (!isAdded || context == null) return
        val glide = Glide.with(this)
        videos.take(12).forEach { video ->
            val isStandardVideo = video.id.isNotEmpty() && !video.id.startsWith("__")
            val url = if (isStandardVideo) {
                "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"
            } else if (video.thumbnailUrl.isNotEmpty()) {
                video.thumbnailUrl
            } else {
                null
            }
            if (url != null) {
                glide.load(url)
                    .override(320, 180)
                    .preload(320, 180)
            }
        }
    }

    /**
     * 登録チャンネル / 後で見る / 視聴履歴 を Room の Flow で監視する (画面表示中のみ)
     * 旧実装は onResume で一度だけ読み込んでいたが、再生画面の履歴保存は onStop (= ホームの onResume より後)
     * に行われるため、戻った直後の履歴が常に 1 つ前の状態になっていた
     */
    private fun observeLocalData() {
        val dao = (requireContext().applicationContext as FireTubeApp).database.videoDao()
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { dao.getAllSubscriptions().collect { renderSubscriptions(it) } }
                launch { dao.getWatchLater().collect { renderWatchLater(it) } }
                launch {
                    dao.getHistoryVideos().collect { history ->
                        renderHistory(history)
                        WatchProgressStore.reload(dao)
                        refreshWatchProgressIfChanged()
                    }
                }
            }
        }
    }

    private var renderedProgressVersion = -1

    /** 視聴済み位置バーに変化があれば表示中のカードを再バインド */
    private fun refreshWatchProgressIfChanged() {
        val version = WatchProgressStore.version
        if (version == renderedProgressVersion) return
        renderedProgressVersion = version
        for (i in 0 until rowsAdapter.size()) {
            val rowAdapter = (rowsAdapter.get(i) as? ListRow)?.adapter as? ArrayObjectAdapter ?: continue
            if (rowAdapter.size() > 0) rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
        }
    }

    private fun renderSubscriptions(subs: List<com.firetube.tv.data.local.SubscriptionEntity>) {
        val subItems = if (subs.isEmpty()) {
            listOf(
                VideoItem(
                    id = ID_NO_SUB,
                    title = getString(R.string.no_subscriptions),
                    uploaderName = getString(R.string.no_subscriptions_desc),
                    thumbnailUrl = ""
                )
            )
        } else {
            subs.map { entity ->
                VideoItem(
                    id = "$PREFIX_CHANNEL${entity.channelId}",
                    title = entity.channelName,
                    uploaderName = "登録チャンネル",
                    thumbnailUrl = entity.channelAvatarUrl ?: ""
                )
            }
        }
        replaceIfChanged(subscriptionsAdapter, subItems)
        loadSubscriptionFeed(subs.map { it.channelId })
    }

    private fun loadSubscriptionFeed(channelKeys: List<String>) {
        subscriptionFeedJob?.cancel()
        subscriptionFeedJob = viewLifecycleOwner.lifecycleScope.launch {
            val feed = VideoRepository.getSubscriptionFeed(channelKeys)
            // 登録チャンネル行の直前に表示
            setOptionalRow(subscriptionFeedRow, feed, beforeAdapter = subscriptionsAdapter)
        }
    }

    /**
     * 内容がある場合のみ表示する行 (空の行ヘッダーを出さないため動的に追加/削除)
     */
    private fun setOptionalRow(row: ListRow, items: List<VideoItem>, beforeAdapter: ArrayObjectAdapter) {
        val rowIndex = rowsAdapter.indexOf(row)
        if (items.isEmpty()) {
            if (rowIndex >= 0) rowsAdapter.removeItems(rowIndex, 1)
            return
        }
        replaceIfChanged(row.adapter as ArrayObjectAdapter, items)
        if (rowIndex < 0) {
            val anchorIndex = (0 until rowsAdapter.size()).firstOrNull {
                (rowsAdapter.get(it) as? ListRow)?.adapter === beforeAdapter
            } ?: rowsAdapter.size()
            rowsAdapter.add(anchorIndex, row)
        }
    }

    private fun renderWatchLater(entities: List<com.firetube.tv.data.local.WatchLaterEntity>) {
        val items = entities.map { e ->
            VideoItem(
                id = e.id,
                title = e.title,
                uploaderName = e.uploaderName,
                uploaderUrl = e.uploaderUrl,
                thumbnailUrl = e.thumbnailUrl,
                durationSeconds = e.durationSeconds
            )
        }
        // 視聴履歴行の直前に表示
        setOptionalRow(watchLaterRow, items, beforeAdapter = historyAdapter)
    }

    private fun renderHistory(historyList: List<com.firetube.tv.data.local.VideoHistoryEntity>) {
        val historyVideos = historyList.map { entity ->
            VideoItem(
                id = entity.id,
                title = entity.title,
                uploaderName = entity.uploaderName,
                uploaderUrl = entity.uploaderUrl,
                thumbnailUrl = entity.thumbnailUrl,
                durationSeconds = entity.durationSeconds
            )
        }
        replaceIfChanged(historyAdapter, historyVideos)
    }

    override fun onResume() {
        super.onResume()
        // 再生画面から戻った際、履歴 DB に変化が無くても (保存前でも) メモリ上の視聴位置の変化を反映
        refreshWatchProgressIfChanged()
    }

    override fun onStop() {
        super.onStop()
        MemoryManager.getInstance()?.clearUiCaches()
    }
}
