package com.firetube.tv.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.extractor.SponsorBlockService
import com.firetube.tv.data.local.SubscriptionEntity
import com.firetube.tv.data.local.VideoHistoryEntity
import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.ChannelKey
import com.firetube.tv.data.model.SponsorSegment
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.model.VideoStream
import com.firetube.tv.data.network.GoogleVideoDataSource
import com.firetube.tv.data.network.NetworkClient
import com.firetube.tv.data.network.ReturnYouTubeDislikeClient
import com.firetube.tv.data.repository.VideoRepository
import com.firetube.tv.ui.main.VideoCardPresenter
import com.firetube.tv.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fire TV 物理リモコン操作対応 高速ネイティブ動画プレイヤー
 * - 広告完全自動フリー（生ストリーム直再生）
 * - SponsorBlock による案件・OP/ED 自動ミリ秒スキップ
 * - 下キーで「関連動画（Up Next）」水平カルーセルを表示しシームレス切り替え
 * - ユーザー設定（画質、再生速度、SponsorBlock対象）の反映
 * - 低RAM最適化（非表示時にSurface描画を即時アンバインド）
 * - 音量均一化 (Loudness Normalizer) 対応
 * - Return YouTube Dislike (RYD) 評価比率バッジ
 * - ハードウェア AVC/VP9 コーデック優先
 */
@OptIn(UnstableApi::class)
class PlaybackActivity : FragmentActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_UPLOADER_NAME = "extra_uploader_name"
        const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        const val EXTRA_UPLOADER_URL = "extra_uploader_url"
        /** 再生リスト等の連続再生キュー (ArrayList<VideoItem>)。指定時は関連動画の代わりにこの順で再生 */
        const val EXTRA_QUEUE = "extra_queue"
        private const val TAG = "PlaybackActivity"

        fun createIntent(context: android.content.Context, item: VideoItem): Intent =
            Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, item.id)
                putExtra(EXTRA_VIDEO_TITLE, item.title)
                putExtra(EXTRA_UPLOADER_NAME, item.uploaderName)
                putExtra(EXTRA_THUMBNAIL_URL, item.thumbnailUrl)
                putExtra(EXTRA_UPLOADER_URL, item.uploaderUrl)
            }
    }

    private var player: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var playbackRoot: View
    private lateinit var playerView: PlayerView
    private lateinit var webViewPlayer: WebView
    private var isUsingWebViewFallback: Boolean = false
    private lateinit var loadingView: ProgressBar
    private lateinit var sponsorBanner: View
    private lateinit var speedIndicator: TextView
    private lateinit var upNextContainer: LinearLayout
    private lateinit var upNextGrid: HorizontalGridView
    private lateinit var autoplayContainer: LinearLayout
    private lateinit var autoplayProgress: ProgressBar
    private lateinit var autoplayText: TextView
    private var autoplayJob: Job? = null
    private var isPlaybackEnded: Boolean = false
    // 現在の videoId のメディアを実際にプレイヤーへ投入済みか (未開始の動画の履歴を位置 0 で上書きしないためのガード)
    private var hasPlaybackStarted: Boolean = false

    private lateinit var videoInfoHud: View
    private lateinit var videoHudTitle: TextView
    private lateinit var videoHudChannel: TextView
    private lateinit var videoHudRyd: TextView
    private var hudDismissJob: Job? = null

    private lateinit var notificationBanner: View
    private lateinit var notificationText: TextView
    private var notificationDismissJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var stallWatchdogJob: Job? = null
    private var isHandlingFallback: Boolean = false
    private var currentStreamInfo: StreamInfoData? = null

    private val upNextAdapter = ArrayObjectAdapter(VideoCardPresenter())

    private var videoId: String = ""
    private var videoTitle: String = ""
    private var uploaderName: String = ""
    private var thumbnailUrl: String = ""
    private var uploaderUrl: String? = null
    private var playQueue: List<VideoItem> = emptyList()

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorMonitorJob: Job? = null
    private var speedHudDismissJob: Job? = null
    private var loadStreamJob: Job? = null
    private var upNextJob: Job? = null
    private var sponsorJob: Job? = null
    private var rydJob: Job? = null

    private lateinit var seekBarContainer: View
    private lateinit var optionsPanel: View
    private lateinit var optionsList: LinearLayout
    private lateinit var seekProgress: ProgressBar
    private lateinit var seekPositionText: TextView
    private lateinit var seekDurationText: TextView
    private var seekBarUpdateJob: Job? = null

    private var trackSelector: DefaultTrackSelector? = null
    private var currentDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null
    // 再生中に画質メニューで選んだ画質 (null = 設定画面の既定画質)。動画を切り替えるとリセット
    private var qualityOverride: String? = null
    // 再生途中の失敗時、WebView へ切り替える前にストリーム URL を 1 回だけ取り直して再開する
    private var hasRetriedStreamRecovery: Boolean = false
    private var isRecoveringStream: Boolean = false

    private val speedList = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        uploaderName = intent.getStringExtra(EXTRA_UPLOADER_NAME) ?: ""
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
        uploaderUrl = intent.getStringExtra(EXTRA_UPLOADER_URL)
        playQueue = readQueue(intent)

        playbackRoot = findViewById(R.id.playback_root)
        playerView = findViewById(R.id.player_view)
        webViewPlayer = findViewById(R.id.web_view_player)
        loadingView = findViewById(R.id.player_loading)
        sponsorBanner = findViewById(R.id.sponsor_banner)
        speedIndicator = findViewById(R.id.speed_indicator)
        upNextContainer = findViewById(R.id.up_next_container)
        upNextGrid = findViewById(R.id.up_next_grid)
        autoplayContainer = findViewById(R.id.autoplay_container)
        autoplayProgress = findViewById(R.id.autoplay_progress)
        autoplayText = findViewById(R.id.autoplay_text)

        videoInfoHud = findViewById(R.id.video_info_hud)
        videoHudTitle = findViewById(R.id.video_hud_title)
        videoHudChannel = findViewById(R.id.video_hud_channel)
        videoHudRyd = findViewById(R.id.video_hud_ryd)
        seekBarContainer = findViewById(R.id.seek_bar_container)
        optionsPanel = findViewById(R.id.options_panel)
        optionsList = findViewById(R.id.options_list)
        seekProgress = findViewById(R.id.seek_progress)
        seekPositionText = findViewById(R.id.seek_position_text)
        seekDurationText = findViewById(R.id.seek_duration_text)

        notificationBanner = findViewById(R.id.notification_banner)
        notificationText = findViewById(R.id.notification_text)

        if (videoId.isEmpty()) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupWebViewPlayer()
        setupUpNextGrid()
        initPlayer()
        startNewVideoSession(videoId, videoTitle, uploaderName, thumbnailUrl, uploaderUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newVideoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return
        Log.i(TAG, "onNewIntent received: videoId=$newVideoId")
        if (newVideoId.isNotEmpty()) {
            val newTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
            val newUploader = intent.getStringExtra(EXTRA_UPLOADER_NAME) ?: ""
            val newThumb = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
            playQueue = readQueue(intent)
            startNewVideoSession(newVideoId, newTitle, newUploader, newThumb, intent.getStringExtra(EXTRA_UPLOADER_URL))
        }
    }

    /**
     * 新しい動画再生セッションのクリーン開始
     * - 前回のストリーム取得・Up Next・SponsorBlock 等の非同期ジョブを即座に全キャンセル
     * - 前回の SponsorBlock スキップ区間・HUD・Up Next を完全リセット
     * - ExoPlayer を停止＆クリアし、SurfaceView 描画を確実に再バインド
     */
    private fun startNewVideoSession(
        targetId: String,
        title: String,
        uploader: String,
        thumb: String,
        channelUrl: String?
    ) {
        // 1. 前回の再生履歴を非同期保存
        // (初回起動時は videoId が既に新しい動画を指しており、ここで保存すると
        //  レジューム位置が 0 で上書きされてしまうため、再生開始済みの場合のみ保存)
        saveHistoryIfStarted()
        hasPlaybackStarted = false

        // 2. 走行中の非同期ジョブを全て即座にキャンセル（Race Condition防止）
        loadStreamJob?.cancel()
        upNextJob?.cancel()
        sponsorJob?.cancel()
        rydJob?.cancel()
        sponsorMonitorJob?.cancel()
        hudDismissJob?.cancel()
        bufferingWatchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        notificationDismissJob?.cancel()
        cancelAutoplay()
        isPlaybackEnded = false

        // 3. SponsorBlock セグメントおよび UI 状態を完全リセット（誤爆スキップ防止）
        sponsorSegments = emptyList()
        sponsorBanner.visibility = View.GONE
        upNextContainer.visibility = View.GONE
        if (::autoplayContainer.isInitialized) {
            autoplayContainer.visibility = View.GONE
        }
        videoInfoHud.visibility = View.GONE
        notificationBanner.visibility = View.GONE
        isHandlingFallback = false
        currentStreamInfo = null
        qualityOverride = null
        hasRetriedStreamRecovery = false
        isRecoveringStream = false
        applyHlsMaxHeight(null)
        hideSeekBar()
        hideOptionsPanel()
        upNextAdapter.clear()

        // 4. ExoPlayer を停止＆キュー・トラックを完全クリア
        isUsingWebViewFallback = false
        if (::webViewPlayer.isInitialized) {
            webViewPlayer.visibility = View.GONE
            webViewPlayer.loadUrl("about:blank")
        }
        playerView.visibility = View.VISIBLE

        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        if (playerView.player == null && player != null) {
            playerView.player = player
        }

        // 5. 新しい動画メタデータをセット
        videoId = targetId
        videoTitle = title
        uploaderName = uploader
        thumbnailUrl = thumb
        uploaderUrl = channelUrl

        loadingView.visibility = View.VISIBLE

        // 6. 各種取得処理を起動
        loadStreamAndPlay()
        loadSponsorBlock()
        loadRydVotes()
        loadUpNextVideos()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewPlayer() {
        // リリースビルドでは chrome://inspect からのリモートデバッグ接続を許可しない
        WebView.setWebContentsDebuggingEnabled(com.firetube.tv.BuildConfig.DEBUG)
        webViewPlayer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webViewPlayer.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webViewPlayer.setBackgroundColor(android.graphics.Color.BLACK)
        webViewPlayer.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webViewPlayer.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewPlayer", "[JS ${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                return true
            }
        }
        webViewPlayer.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onPlayerReady() {
                runOnUiThread {
                    Log.i(TAG, "WebView IFrame player ready. Hiding loadingView and showing HUD.")
                    loadingView.visibility = View.GONE
                    showVideoInfoHud()
                }
            }

            @android.webkit.JavascriptInterface
            fun onPlayerStateChange(state: Int) {
                runOnUiThread {
                    Log.d(TAG, "WebView IFrame player state: $state")
                    when (state) {
                        1 -> { // PLAYING
                            loadingView.visibility = View.GONE
                        }
                        2 -> { // PAUSED
                        }
                        3 -> { // BUFFERING
                            loadingView.visibility = View.VISIBLE
                        }
                        0 -> { // ENDED
                            handleVideoEnded()
                        }
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun onPlayerError(errorCode: Int) {
                Log.e(TAG, "WebView IFrame player error code: $errorCode")
            }
        }, "FireTubeBridge")
    }

    private fun loadIframeVideo(vId: String, startSec: Float) {
        // vId は下記 JavaScript に直接埋め込まれるため、YouTube の ID 文字種以外は拒否する
        if (!vId.matches(Regex("^[A-Za-z0-9_-]{11}$"))) {
            Log.e(TAG, "Refusing to load IFrame player for invalid videoId: $vId")
            loadingView.visibility = View.GONE
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
              html, body { margin: 0; padding: 0; width: 100vw; height: 100vh; background-color: #000; overflow: hidden; }
              #player, iframe { position: absolute; top: 0; left: 0; width: 100vw; height: 100vh; border: none; }
            </style>
            </head>
            <body>
            <div id="player"></div>
            <script>
              // Polyfill queueMicrotask for older WebViews (Chrome < 71 / Android 9)
              if (typeof window.queueMicrotask !== 'function') {
                window.queueMicrotask = function(cb) {
                  Promise.resolve().then(cb).catch(function(err) {
                    setTimeout(function() { throw err; }, 0);
                  });
                };
              }

              var tag = document.createElement('script');
              tag.src = "https://www.youtube.com/iframe_api";
              var firstScriptTag = document.getElementsByTagName('script')[0];
              firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

              var player = null;
              var targetVideoId = '$vId';
              var targetStartSec = $startSec;

              function onYouTubeIframeAPIReady() {
                console.log("YouTube IFrame API Ready. Creating player for: " + targetVideoId + " at " + targetStartSec + "s");
                player = new YT.Player('player', {
                  videoId: targetVideoId,
                  playerVars: {
                    'autoplay': 1,
                    'controls': 0,
                    'disablekb': 1,
                    'fs': 0,
                    'rel': 0,
                    'modestbranding': 1,
                    'iv_load_policy': 3,
                    'start': Math.floor(targetStartSec),
                    'playsinline': 1,
                    'enablejsapi': 1,
                    'origin': 'https://www.youtube-nocookie.com'
                  },
                  events: {
                    'onReady': onPlayerReady,
                    'onStateChange': onPlayerStateChange,
                    'onError': onPlayerError
                  }
                });
              }

              function onPlayerReady(event) {
                console.log("IFrame player event: ready");
                event.target.playVideo();
                if (window.FireTubeBridge) {
                  window.FireTubeBridge.onPlayerReady();
                }
              }

              function onPlayerStateChange(event) {
                console.log("IFrame player event: state=" + event.data);
                if (window.FireTubeBridge) {
                  window.FireTubeBridge.onPlayerStateChange(event.data);
                }
              }

              function onPlayerError(event) {
                console.error("IFrame player event: error=" + event.data);
                if (window.FireTubeBridge) {
                  window.FireTubeBridge.onPlayerError(event.data);
                }
              }

              function togglePlay() {
                if (!player || !player.getPlayerState) return;
                var s = player.getPlayerState();
                if (s === 1) {
                  player.pauseVideo();
                } else {
                  player.playVideo();
                }
              }

              function seekRelative(sec) {
                if (!player || !player.getCurrentTime) return;
                var cur = player.getCurrentTime();
                var dur = player.getDuration ? player.getDuration() : 999999;
                var target = Math.max(0, Math.min(dur, cur + sec));
                player.seekTo(target, true);
              }
            </script>
            </body>
            </html>
        """.trimIndent()
        webViewPlayer.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
    }

    private fun switchToIframeFallback(startMs: Long) {
        if (isUsingWebViewFallback) return
        isUsingWebViewFallback = true
        hasPlaybackStarted = true
        Log.i(TAG, "Switching to WebView IFrame fallback at pos=${startMs}ms for video: $videoId")

        runOnUiThread {
            bufferingWatchdogJob?.cancel()
            stallWatchdogJob?.cancel()

            // 1. ExoPlayer 停止
            player?.pause()
            player?.stop()
            playerView.player = null
            playerView.visibility = View.GONE

            // 2. WebView 表示 & 続きから再生
            val startSec = (startMs / 1000f).coerceAtLeast(0f)
            webViewPlayer.visibility = View.VISIBLE
            webViewPlayer.bringToFront()
            loadIframeVideo(videoId, startSec)
            showStatusNotification(getString(R.string.switching_to_compatible_mode))
        }
    }

    private fun setupUpNextGrid() {
        val bridgeAdapter = ItemBridgeAdapter(upNextAdapter)
        upNextGrid.adapter = bridgeAdapter
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos < upNextAdapter.size()) {
                        val item = upNextAdapter.get(pos) as? VideoItem
                        if (item != null) {
                            cancelAutoplay()
                            switchVideo(item)
                        }
                    }
                }
            }
        })
        upNextGrid.setOnChildViewHolderSelectedListener(object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: androidx.recyclerview.widget.RecyclerView,
                child: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                if (position > 0) {
                    cancelAutoplay()
                }
            }
        })
    }

    private var currentMediaSourceFactory: DefaultMediaSourceFactory? = null

    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = lifecycleScope.launch {
            var lastPos = -1L
            var stallCount = 0
            while (isActive) {
                delay(1000)
                val p = player ?: break
                if (isUsingWebViewFallback) break

                // 再生指示が出ている状態で確認
                if (p.playWhenReady && p.playbackState == Player.STATE_READY) {
                    val curPos = p.currentPosition
                    if (curPos > 0 && kotlin.math.abs(curPos - lastPos) < 200L) {
                        stallCount++
                        Log.d(TAG, "Stall watchdog: position unchanged at ${curPos}ms (count=$stallCount)")
                        if (stallCount >= 3) {
                            Log.w(TAG, "Playback stalled for 3s at pos=${curPos}ms!")
                            recoverOrFallback(curPos)
                            break
                        }
                    } else {
                        stallCount = 0
                        lastPos = curPos
                    }
                } else {
                    stallCount = 0
                    lastPos = -1L
                }
            }
        }
    }

    private fun initPlayer() {
        val pref = AppPreferences.getInstance(this)
        val isHigh = com.firetube.tv.util.DeviceProfileManager.isHighPerformance(this)

        val trackSelector = DefaultTrackSelector(this).apply {
            var paramsBuilder = buildUponParameters()
            if (isHigh) {
                // 4K Max 向け: ハードウェアオーディオ/ビデオ同期 (AV同期) の最適化
                paramsBuilder = paramsBuilder.setTunnelingEnabled(true)
            }
            if (pref.preferAvcCodec && !isHigh) {
                paramsBuilder = paramsBuilder.setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
            }
            // 字幕トラックは常に読み込み、表示/非表示はテキストレンダラーの有効化で即時切替
            paramsBuilder = paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !pref.subtitlesEnabled)
            parameters = paramsBuilder.build()
        }
        this.trackSelector = trackSelector

        // YouTube CDN からの 403 Forbidden（1MB制限）を即時キャッチしてフォールバック
        GoogleVideoDataSource.onStreamForbiddenListener = { _, _ ->
            runOnUiThread {
                if (!isUsingWebViewFallback && !isFinishing && !isDestroyed) {
                    val currentPos = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
                    Log.w(TAG, "GoogleVideoDataSource 403 Forbidden received at pos=${currentPos}ms.")
                    recoverOrFallback(currentPos)
                }
            }
        }

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(NetworkClient.client)
        val googleVideoDataSourceFactory = GoogleVideoDataSource.Factory(NetworkClient.client, okHttpDataSourceFactory)
        val cachedDataSourceFactory = ExoPlayerCacheManager.createCacheDataSourceFactory(this, googleVideoDataSourceFactory)

        currentDataSourceFactory = cachedDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(cachedDataSourceFactory)
        currentMediaSourceFactory = mediaSourceFactory

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(PlayerLoadControlFactory.createAdaptiveLoadControl(this, pref.bufferProfile))
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.i(TAG, "onPlaybackStateChanged: $state (BUFFERING=2, READY=3, ENDED=4, IDLE=1)")
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                loadingView.visibility = View.VISIBLE
                                // 再生途中でバッファ枯渇に陥った場合の監視（YouTube PoToken 403 制限のフォールバック）
                                val pos = player?.currentPosition ?: 0L
                                val isHls = currentStreamInfo?.hlsUrl != null
                                if (pos > 10_000L && !isHls && !isUsingWebViewFallback) {
                                    bufferingWatchdogJob?.cancel()
                                    bufferingWatchdogJob = lifecycleScope.launch {
                                        delay(3000)
                                        if (isActive && player?.playbackState == Player.STATE_BUFFERING && !isUsingWebViewFallback) {
                                            val currentPos = player?.currentPosition ?: 0L
                                            Log.w(TAG, "Playback stalled mid-stream at pos=${currentPos}ms.")
                                            recoverOrFallback(currentPos)
                                        }
                                    }
                                }
                            }
                            Player.STATE_READY -> {
                                bufferingWatchdogJob?.cancel()
                                Log.i(TAG, "Playback ready! Hiding loadingView and showing HUD")
                                loadingView.visibility = View.GONE
                                showVideoInfoHud()
                                startStallWatchdog()
                            }
                            Player.STATE_ENDED -> {
                                handleVideoEnded()
                            }
                            Player.STATE_IDLE -> {
                                bufferingWatchdogJob?.cancel()
                                stallWatchdogJob?.cancel()
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer error [${error.errorCodeName} / ${error.errorCode}]: ${error.message}", error)
                        bufferingWatchdogJob?.cancel()
                        VideoRepository.invalidateStreamCache(videoId)
                        loadingView.visibility = View.GONE

                        // HTTP 403 Forbidden（YouTube CDN ストリーミング制限）または再生途中エラーの即座自動復旧
                        val isHttp403 = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                error.message?.contains("403") == true ||
                                error.cause?.message?.contains("403") == true

                        val pos = player?.currentPosition ?: 0L
                        if ((isHttp403 || pos > 5_000L) && !isUsingWebViewFallback) {
                            Log.w(TAG, "Player error encountered mid-playback at pos=${pos}ms.")
                            recoverOrFallback(pos)
                            return
                        }

                        val message = when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> getString(R.string.network_error_msg)
                            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED -> "デコーダー非対応または再生エラーが発生しました"
                            else -> getString(R.string.error_loading)
                        }
                        Toast.makeText(this@PlaybackActivity, message, Toast.LENGTH_SHORT).show()
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                            setupLoudnessEnhancer(audioSessionId)
                        }
                    }
                })
            }
        playerView.player = player

        // 設定されたデフォルト速度を適用 (長押しでの速度サイクルが既定速度の次から始まるようインデックスも同期)
        currentSpeedIndex = speedList.indexOf(pref.defaultSpeed).coerceAtLeast(0)
        applyPlaybackSpeed(pref.defaultSpeed)
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        val pref = AppPreferences.getInstance(this)
        if (!pref.loudnessNormalizerEnabled) {
            loudnessEnhancer?.enabled = false
            return
        }

        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(1000) // 10dB相当の自動リミッター・ゲインノーマライズ
                enabled = true
            }
            Log.i(TAG, "LoudnessEnhancer enabled for session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer unsupported on this hardware: ${e.message}")
        }
    }

    private fun loadRydVotes() {
        val pref = AppPreferences.getInstance(this)
        if (!pref.showRydVotes) {
            videoHudRyd.visibility = View.GONE
            return
        }

        val currentTargetId = videoId
        rydJob = lifecycleScope.launch {
            val result = ReturnYouTubeDislikeClient.getVotes(currentTargetId)
            if (!isActive || videoId != currentTargetId) return@launch
            result.onSuccess { votes ->
                videoHudRyd.text = votes.formattedSummary
                videoHudRyd.visibility = View.VISIBLE
            }.onFailure {
                videoHudRyd.visibility = View.GONE
            }
        }
    }

    private fun showVideoInfoHud() {
        videoHudTitle.text = videoTitle
        videoHudChannel.text = uploaderName
        videoInfoHud.visibility = View.VISIBLE
        videoInfoHud.alpha = 1.0f

        showSeekBar()

        hudDismissJob?.cancel()
        hudDismissJob = lifecycleScope.launch {
            delay(4000)
            videoInfoHud.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    videoInfoHud.visibility = View.GONE
                    hideSeekBar()
                }
                .start()
        }
    }

    private fun loadStreamAndPlay() {
        loadingView.visibility = View.VISIBLE
        val currentTargetId = videoId
        loadStreamJob = lifecycleScope.launch {
            val result = VideoRepository.extractStreamInfo(currentTargetId)
            if (!isActive || videoId != currentTargetId) return@launch
            result.onSuccess { streamInfo ->
                startPlayback(streamInfo)
            }.onFailure { e ->
                Log.e(TAG, "Stream extraction error for $currentTargetId: ${e.message}", e)
                val errMsg = e.message ?: ""
                val isUpcomingOrOffline = errMsg.contains("プレミア") ||
                        errMsg.contains("ライブ配信") ||
                        errMsg.contains("開始予定") ||
                        errMsg.contains("OFFLINE")
                val isPrivateOrDeleted = errMsg.contains("非公開") ||
                        errMsg.contains("削除") ||
                        errMsg.contains("ご覧いただけません")

                if (isPrivateOrDeleted || isUpcomingOrOffline) {
                    val noticeText = when {
                        isPrivateOrDeleted -> "この動画は非公開または削除されているため再生できません"
                        else -> if (errMsg.contains("プレミア") || errMsg.contains("開始予定")) errMsg else "この動画は現在プレミア公開前または配信準備中です"
                    }
                    loadingView.visibility = View.GONE
                    showStatusNotification(noticeText)
                    Toast.makeText(this@PlaybackActivity, noticeText, Toast.LENGTH_LONG).show()
                } else if (!isUsingWebViewFallback && !isFinishing && !isDestroyed) {
                    Log.w(TAG, "Stream extraction failed for $currentTargetId. Auto-recovering via WebView IFrame fallback.")
                    switchToIframeFallback(0L)
                } else {
                    loadingView.visibility = View.GONE
                    val errorMsg = when {
                        errMsg.contains("network", ignoreCase = true) -> getString(R.string.network_error_msg)
                        else -> getString(R.string.error_loading)
                    }
                    Toast.makeText(this@PlaybackActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * @param resumePositionMs 指定時は保存済みレジューム位置ではなくこの位置から再生 (画質変更・ストリーム再取得時)
     */
    private fun startPlayback(streamInfo: StreamInfoData, resumePositionMs: Long? = null) {
        currentStreamInfo = streamInfo
        val pref = AppPreferences.getInstance(this)
        val targetQuality = qualityOverride ?: pref.defaultQuality

        Log.i(TAG, "startPlayback: videoStreams=${streamInfo.videoStreams.size}, audioStreams=${streamInfo.audioStreams.size}, hlsUrl=${streamInfo.hlsUrl != null}")
        streamInfo.videoStreams.forEachIndexed { i, s ->
            Log.d(TAG, "Stream[$i]: res=${s.resolution}, fmt=${s.format}, videoOnly=${s.isVideoOnly}")
        }

        val durationMs = streamInfo.durationSeconds * 1000L

        // 1. HLS (アダプティブビットレート) を最優先（ライブ配信および対応VOD）
        if (streamInfo.hlsUrl != null) {
            Log.i(TAG, "Playing via HLS adaptive stream: ${streamInfo.hlsUrl.take(80)}")
            val mediaItem = MediaItem.Builder()
                .setUri(streamInfo.hlsUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            executePlayback(mediaItem = mediaItem, durationMs = durationMs, resumePositionMs = resumePositionMs)
            return
        }

        // 2. 音声付き単一ストリーム (Muxed) を検索
        val muxedStreams = streamInfo.videoStreams.filter { !it.isVideoOnly }
        val qualityFilteredMuxed = muxedStreams.filter { it.resolution.contains(targetQuality.take(4), ignoreCase = true) }
        val bestMuxed = if (qualityFilteredMuxed.isNotEmpty()) qualityFilteredMuxed.first() else muxedStreams.firstOrNull()

        // 3. DASH セパレートストリーム (映像ストリーム + 音声ストリームの合成)
        val bestVideo = selectBestVideoStream(streamInfo.videoStreams, targetQuality, pref.preferAvcCodec)
        val bestAudio = selectBestAudioStream(streamInfo.audioStreams)
        val factory = currentMediaSourceFactory

        if (bestVideo != null && bestAudio != null && factory != null) {
            Log.i(TAG, "Playing via DASH MergingMediaSource: video=${bestVideo.resolution} (${bestVideo.format}), audio=${bestAudio.format} (${bestAudio.bitrate}bps)")
            val videoSource = factory.createMediaSource(MediaItem.fromUri(bestVideo.url))
            val audioSource = factory.createMediaSource(MediaItem.fromUri(bestAudio.url))
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            executePlayback(mediaSource = mergedSource, durationMs = durationMs, resumePositionMs = resumePositionMs)
        } else if (bestMuxed != null) {
            Log.i(TAG, "Playing via Muxed stream: ${bestMuxed.resolution} (${bestMuxed.format})")
            val mediaItem = MediaItem.fromUri(bestMuxed.url)
            executePlayback(mediaItem = mediaItem, durationMs = durationMs, resumePositionMs = resumePositionMs)
        } else if (bestVideo != null) {
            Log.w(TAG, "Playing video-only stream (audio stream missing): ${bestVideo.resolution}")
            val mediaItem = MediaItem.fromUri(bestVideo.url)
            executePlayback(mediaItem = mediaItem, durationMs = durationMs, resumePositionMs = resumePositionMs)
        } else {
            Log.e(TAG, "No playable stream found for video: $videoId. Auto-recovering via WebView IFrame fallback.")
            if (!isUsingWebViewFallback && !isFinishing && !isDestroyed) {
                switchToIframeFallback(0L)
            } else {
                loadingView.visibility = View.GONE
                Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectBestVideoStream(
        streams: List<VideoStream>,
        targetQuality: String,
        preferAvc: Boolean
    ): VideoStream? {
        if (streams.isEmpty()) return null

        val qualityKeyword = when {
            targetQuality.contains("4K", ignoreCase = true) || targetQuality.contains("2160") -> "2160"
            targetQuality.contains("1440") -> "1440"
            targetQuality.contains("1080") -> "1080"
            targetQuality.contains("720") -> "720"
            targetQuality.contains("480") -> "480"
            else -> "720"
        }

        val matchedByQuality = streams.filter { it.resolution.contains(qualityKeyword) }
        val pool = if (matchedByQuality.isNotEmpty()) matchedByQuality else streams

        // 4K (2160p) または 1440p は YouTube の仕様上 AVC (H.264) が存在しないため、
        // preferAvc 設定に関わらず VP9 / AV1 ストリームをフル活用
        val isHighResolution = qualityKeyword == "2160" || qualityKeyword == "1440"

        return if (preferAvc && !isHighResolution) {
            pool.firstOrNull { it.format.equals("mp4", ignoreCase = true) || it.url.contains("mime=video%2Fmp4") }
                ?: pool.firstOrNull()
        } else {
            // 4K Max 等の高スペック環境では最高ビットレート（60fps / 高品質）ストリームを優先
            pool.maxByOrNull { it.bitrate } ?: pool.firstOrNull()
        }
    }

    private fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? {
        if (streams.isEmpty()) return null
        // Fire TV ハードウェアデコーダー互換性と YouTube CDN 安定性が最も高い m4a (AAC) を最優先
        val m4aStreams = streams.filter { it.format.equals("m4a", ignoreCase = true) }
        if (m4aStreams.isNotEmpty()) {
            return m4aStreams.maxByOrNull { it.bitrate } ?: m4aStreams.first()
        }
        return streams.maxByOrNull { it.bitrate } ?: streams.firstOrNull()
    }

    private fun executePlayback(
        mediaItem: MediaItem? = null,
        mediaSource: MediaSource? = null,
        durationMs: Long,
        resumePositionMs: Long? = null
    ) {
        val currentTargetId = videoId
        val subtitleSource = buildSubtitleSource(currentStreamInfo)
        lifecycleScope.launch {
            val lastPos = resumePositionMs ?: (application as FireTubeApp).database.videoDao().getLastPosition(currentTargetId)
            Log.i(TAG, "Restoring position for $currentTargetId: lastPos=$lastPos, durationMs=$durationMs")
            if (!isActive || videoId != currentTargetId) return@launch

            // 字幕トラックがあれば本体ストリームと合成 (字幕の読み込み失敗で再生全体が止まらないよう EOS 扱い)
            val baseSource = mediaSource ?: mediaItem?.let { currentMediaSourceFactory?.createMediaSource(it) }
            val finalSource = if (subtitleSource != null && baseSource != null) {
                MergingMediaSource(baseSource, subtitleSource)
            } else {
                baseSource
            }

            player?.let { p ->
                if (playerView.player == null) {
                    playerView.player = p
                }
                hasPlaybackStarted = true
                p.clearMediaItems()
                if (finalSource != null) {
                    p.setMediaSource(finalSource, /* resetPosition = */ true)
                } else if (mediaItem != null) {
                    p.setMediaItem(mediaItem, /* resetPosition = */ true)
                }
                val shouldSeek = if (resumePositionMs != null) {
                    resumePositionMs > 0
                } else {
                    lastPos != null && lastPos > 5000 && (durationMs <= 0 || lastPos < durationMs - 10000)
                }
                if (shouldSeek) {
                    Log.i(TAG, "Seeking to saved position: $lastPos ms")
                    p.seekTo(lastPos!!)
                } else {
                    p.seekTo(0)
                }
                p.playWhenReady = true
                p.prepare()
                p.play()
                startSponsorMonitor()
            }
        }
    }

    /**
     * 字幕トラックを選択して SingleSampleMediaSource を生成
     * 優先順: 日本語 (手動) → 日本語 (自動生成) → 英語 (手動) → 英語 (自動生成)
     */
    private fun buildSubtitleSource(streamInfo: StreamInfoData?): MediaSource? {
        val tracks = streamInfo?.subtitles ?: return null
        val dataSourceFactory = currentDataSourceFactory ?: return null
        val track = listOf("ja", "en").firstNotNullOfOrNull { lang ->
            val langTracks = tracks.filter { it.languageCode.equals(lang, true) || it.languageCode.startsWith("$lang-", true) }
            langTracks.firstOrNull { !it.isAutoGenerated } ?: langTracks.firstOrNull()
        } ?: return null

        // timedtext は既定で XML 形式のため WebVTT を明示指定 (既存の fmt パラメータは除去)
        val baseUrl = track.url.replace(Regex("[?&]fmt=[^&]*"), "")
        val vttUrl = baseUrl + (if (baseUrl.contains('?')) "&" else "?") + "fmt=vtt"
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(vttUrl))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(track.languageCode)
            .setLabel(track.languageName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        Log.i(TAG, "Subtitle track attached: ${track.languageCode} (auto=${track.isAutoGenerated})")
        return androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(dataSourceFactory)
            .setTreatLoadErrorsAsEndOfStream(true)
            .createMediaSource(subtitleConfig, C.TIME_UNSET)
    }

    /**
     * 再生途中の失敗 (403 / 停止 / バッファ枯渇 / プレイヤーエラー) からの復旧
     * YouTube のストリーム URL は失効するため、まず 1 回だけ新しい URL を取り直して同じ位置から再開し、
     * それでも失敗した場合に WebView IFrame 再生へ切り替える
     * (旧実装は一時的な回線の遅延でも即座に低機能な WebView 再生へ切り替わっていた)
     */
    private fun recoverOrFallback(positionMs: Long) {
        if (isUsingWebViewFallback || isFinishing || isDestroyed || isRecoveringStream) return
        if (hasRetriedStreamRecovery) {
            Log.w(TAG, "Stream recovery already attempted. Switching to WebView IFrame fallback at pos=${positionMs}ms.")
            switchToIframeFallback(positionMs)
            return
        }
        hasRetriedStreamRecovery = true
        isRecoveringStream = true
        bufferingWatchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        loadingView.visibility = View.VISIBLE
        Log.w(TAG, "Re-extracting stream URLs for $videoId and resuming at ${positionMs}ms.")

        val targetId = videoId
        VideoRepository.invalidateStreamCache(targetId)
        loadStreamJob?.cancel()
        loadStreamJob = lifecycleScope.launch {
            val result = VideoRepository.extractStreamInfo(targetId)
            if (!isActive || videoId != targetId) return@launch
            isRecoveringStream = false
            result.onSuccess { info ->
                startPlayback(info, resumePositionMs = positionMs)
            }.onFailure {
                switchToIframeFallback(positionMs)
            }
        }
    }

    /** HLS の最大画質を制限 (null = 制限なし)。HLS は再生を止めずに ABR が切り替える */
    private fun applyHlsMaxHeight(quality: String?) {
        val selector = trackSelector ?: return
        val height = quality?.let { q -> Regex("\\d{3,4}").find(q)?.value?.toIntOrNull() }
        selector.parameters = selector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, height ?: Int.MAX_VALUE)
            .build()
    }

    /**
     * 再生中メニュー (HUD 表示中に ↑ キー): 画質の切替と字幕の表示切替
     */
    private fun showPlayerOptionsMenu() {
        val info = currentStreamInfo ?: return
        val pref = AppPreferences.getInstance(this)
        val isHls = info.hlsUrl != null
        val qualityLabels = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p").filter { q ->
            // HLS はマニフェスト内の画質が事前に分からないため全候補を表示
            isHls || info.videoStreams.any { it.resolution.contains(q.removeSuffix("p")) }
        }
        val hasSubtitles = info.subtitles.isNotEmpty()
        val currentQuality = qualityOverride ?: if (isHls) null else pref.defaultQuality

        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add((if (currentQuality == null) "● 画質: 自動" else "画質: 自動") to { applyQuality(null) })
        qualityLabels.forEach { q ->
            val label = if (currentQuality?.contains(q.removeSuffix("p")) == true) "● 画質: $q" else "画質: $q"
            items.add(label to { applyQuality(q) })
        }
        if (hasSubtitles) {
            items.add((if (pref.subtitlesEnabled) "字幕: ON → OFF にする" else "字幕: OFF → ON にする") to { toggleSubtitles() })
        }

        optionsList.removeAllViews()
        val density = resources.displayMetrics.density
        items.forEach { (label, action) ->
            val itemView = TextView(this).apply {
                text = label
                textSize = 18f
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@PlaybackActivity, R.color.options_item_text))
                setBackgroundResource(R.drawable.btn_selector)
                val padH = (16 * density).toInt()
                val padV = (12 * density).toInt()
                setPadding(padH, padV, padH, padV)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    hideOptionsPanel()
                    action()
                }
            }
            optionsList.addView(itemView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() })
        }
        // 先頭/末尾で上下キーを押してもパネル外 (動画面) へフォーカスが抜けないよう固定
        for (i in 0 until optionsList.childCount) {
            optionsList.getChildAt(i).id = View.generateViewId()
        }
        optionsList.getChildAt(0)?.let { it.nextFocusUpId = it.id }
        optionsList.getChildAt(optionsList.childCount - 1)?.let { it.nextFocusDownId = it.id }
        optionsPanel.visibility = View.VISIBLE
        (items.indexOfFirst { it.first.startsWith("●") }.takeIf { it >= 0 } ?: 0).let { index ->
            optionsList.getChildAt(index)?.requestFocus()
        }
    }

    private fun hideOptionsPanel() {
        if (optionsPanel.visibility != View.VISIBLE) return
        optionsPanel.visibility = View.GONE
        if (isUsingWebViewFallback || playerView.visibility != View.VISIBLE) {
            playbackRoot.requestFocus()
        } else {
            playerView.requestFocus()
        }
    }

    private fun applyQuality(quality: String?) {
        val info = currentStreamInfo ?: return
        val p = player ?: return
        qualityOverride = quality
        if (info.hlsUrl != null) {
            applyHlsMaxHeight(quality)
        } else {
            // DASH / Muxed はストリーム URL 自体が画質ごとに異なるため、現在位置から再構築
            startPlayback(info, resumePositionMs = p.currentPosition)
        }
        showStatusNotification("画質: ${quality ?: "自動"}")
    }

    private fun toggleSubtitles() {
        val pref = AppPreferences.getInstance(this)
        val enabled = !pref.subtitlesEnabled
        pref.subtitlesEnabled = enabled
        trackSelector?.let { selector ->
            selector.parameters = selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                .build()
        }
        showStatusNotification(if (enabled) "字幕を表示します" else "字幕を非表示にしました")
    }

    private fun showSeekBar() {
        val p = player ?: return
        if (isUsingWebViewFallback || p.currentMediaItem == null || p.isCurrentMediaItemLive) {
            hideSeekBar()
            return
        }
        seekBarContainer.visibility = View.VISIBLE
        seekBarContainer.alpha = 1.0f
        seekBarUpdateJob?.cancel()
        seekBarUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateSeekBar()
                delay(500)
            }
        }
    }

    private fun hideSeekBar() {
        seekBarUpdateJob?.cancel()
        seekBarUpdateJob = null
        seekBarContainer.visibility = View.GONE
    }

    private fun updateSeekBar() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)
        seekPositionText.text = formatTime(position)
        if (duration != C.TIME_UNSET && duration > 0) {
            seekDurationText.text = formatTime(duration)
            seekProgress.progress = (position * 1000 / duration).toInt()
            seekProgress.secondaryProgress = (p.bufferedPosition * 1000 / duration).toInt()
        } else {
            seekDurationText.text = "--:--"
            seekProgress.progress = 0
            seekProgress.secondaryProgress = 0
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun loadSponsorBlock() {
        val currentTargetId = videoId
        sponsorJob = lifecycleScope.launch {
            val segments = SponsorBlockService.getSkipSegments(currentTargetId)
            if (isActive && videoId == currentTargetId) {
                sponsorSegments = segments
                Log.d(TAG, "Loaded ${segments.size} SponsorBlock segments for $currentTargetId")
            }
        }
    }

    private fun loadUpNextVideos() {
        val upNextTitle = findViewById<TextView>(R.id.text_up_next_title)
        if (playQueue.isNotEmpty()) {
            // 再生リストの続きを Up Next として即時表示 (自動再生もこの順に進む)
            upNextTitle.text = getString(R.string.up_next_queue)
            upNextAdapter.clear()
            upNextAdapter.addAll(0, playQueue)
            return
        }
        upNextTitle.text = getString(R.string.up_next)
        // ⑦ 旧: 2500ms 遅延 → 新: 800ms 遅延
        // 初期ストリーム取得は ~500ms で完了するため、800ms でも帯域競合せず大幅に早く取得可能
        val currentTargetId = videoId
        upNextJob = lifecycleScope.launch {
            delay(800)
            if (!isActive || videoId != currentTargetId) return@launch
            val result = VideoRepository.getUpNextVideos(currentTargetId)
            if (!isActive || videoId != currentTargetId) return@launch
            result.onSuccess { videos ->
                upNextAdapter.clear()
                upNextAdapter.addAll(0, videos)
                if (isPlaybackEnded && upNextContainer.visibility != View.VISIBLE) {
                    showUpNextPanel(isEnded = true)
                }
            }
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun readQueue(intent: Intent?): List<VideoItem> =
        (intent?.getSerializableExtra(EXTRA_QUEUE) as? ArrayList<VideoItem>) ?: emptyList()

    private fun switchVideo(item: VideoItem) {
        // キュー内の動画へ進んだ場合は残りをキューとして維持、それ以外 (関連動画) はキュー再生を終了
        val queueIndex = playQueue.indexOfFirst { it.id == item.id }
        playQueue = if (queueIndex >= 0) playQueue.drop(queueIndex + 1) else emptyList()
        startNewVideoSession(item.id, item.title, item.uploaderName, item.thumbnailUrl, item.uploaderUrl)
    }

    private fun showStatusNotification(message: String) {
        notificationText.text = message
        notificationBanner.visibility = View.VISIBLE
        notificationBanner.alpha = 1.0f

        notificationDismissJob?.cancel()
        notificationDismissJob = lifecycleScope.launch {
            delay(5000)
            notificationBanner.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    notificationBanner.visibility = View.GONE
                }
                .start()
        }
    }

    private fun showUpNextPanel(isEnded: Boolean = false) {
        if (upNextAdapter.size() == 0) {
            if (isEnded) isPlaybackEnded = true
            return
        }
        upNextContainer.visibility = View.VISIBLE
        upNextGrid.requestFocus()

        val pref = AppPreferences.getInstance(this)
        if (isEnded && pref.autoplayNext) {
            startAutoplayCountdown()
        } else {
            cancelAutoplay()
        }
    }

    private fun handleVideoEnded() {
        bufferingWatchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        isPlaybackEnded = true
        val pos = player?.currentPosition ?: 0L
        saveHistory(pos)
        showUpNextPanel(isEnded = true)
    }

    private fun startAutoplayCountdown() {
        cancelAutoplay()
        if (upNextAdapter.size() == 0) return
        val nextVideo = upNextAdapter.get(0) as? VideoItem ?: return

        autoplayContainer.visibility = View.VISIBLE
        autoplayProgress.max = 50
        autoplayProgress.progress = 50

        autoplayJob = lifecycleScope.launch {
            for (tick in 50 downTo 0) {
                if (!isActive) break
                val secRemaining = (tick * 100 + 999) / 1000
                autoplayProgress.progress = tick
                autoplayText.text = "次の動画を自動再生 (${secRemaining}秒)"
                delay(100)
            }
            if (isActive) {
                cancelAutoplay()
                Log.i(TAG, "Autoplay countdown finished. Autoplaying next video: ${nextVideo.id}")
                switchVideo(nextVideo)
            }
        }
    }

    private fun cancelAutoplay() {
        autoplayJob?.cancel()
        autoplayJob = null
        if (::autoplayContainer.isInitialized) {
            autoplayContainer.visibility = View.GONE
        }
    }

    private fun hideUpNextPanel() {
        cancelAutoplay()
        upNextContainer.visibility = View.GONE
        if (isUsingWebViewFallback || playerView.visibility != View.VISIBLE) {
            playbackRoot.requestFocus()
        } else {
            playerView.requestFocus()
        }
    }

    /**
     * SponsorBlock 自動スキップループ
     */
    private fun startSponsorMonitor() {
        sponsorMonitorJob?.cancel()
        val pref = AppPreferences.getInstance(this)

        sponsorMonitorJob = lifecycleScope.launch {
            while (isActive) {
                player?.let { p ->
                    if (p.isPlaying && sponsorSegments.isNotEmpty()) {
                        val currentMs = p.currentPosition
                        val segmentToSkip = sponsorSegments.firstOrNull { it.contains(currentMs) }
                        if (segmentToSkip != null) {
                            val shouldSkip = when (segmentToSkip.category) {
                                "sponsor" -> pref.skipSponsor
                                "intro" -> pref.skipIntro
                                "outro" -> pref.skipOutro
                                "selfpromo" -> pref.skipSelfPromo
                                "interaction" -> pref.skipInteraction
                                else -> false
                            }
                            if (shouldSkip) {
                                Log.i(TAG, "SponsorBlock match: Skipping to ${segmentToSkip.endMs}ms (${segmentToSkip.category})")
                                p.seekTo(segmentToSkip.endMs + 100)
                                if (pref.showSponsorBadge) {
                                    showSponsorBanner()
                                }
                            }
                        }
                    }
                }
                delay(150)
            }
        }
    }

    private fun showSponsorBanner() {
        sponsorBanner.visibility = View.VISIBLE
        sponsorBanner.alpha = 1.0f
        sponsorBanner.animate()
            .alpha(0f)
            .setDuration(2500)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sponsorBanner.visibility = View.GONE
                }
            })
            .start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 再生オプションパネル表示中: 上下キーのフォーカス移動・決定は各項目に任せ、戻る/左で閉じる
        if (optionsPanel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                hideOptionsPanel()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        // Up Next パネル表示中のキー処理（ExoPlayer / WebView 共通）
        if (upNextContainer.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    // 自動再生カウントダウン中なら即座に次の動画を即時再生
                    if (autoplayJob?.isActive == true) {
                        cancelAutoplay()
                        val nextVideo = upNextAdapter.get(0) as? VideoItem
                        if (nextVideo != null) {
                            switchVideo(nextVideo)
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    hideUpNextPanel()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    onBackPressed()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isUsingWebViewFallback && ::webViewPlayer.isInitialized && webViewPlayer.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    webViewPlayer.evaluateJavascript("seekRelative(-10);", null)
                    showVideoInfoHud()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    webViewPlayer.evaluateJavascript("seekRelative(10);", null)
                    showVideoInfoHud()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    webViewPlayer.evaluateJavascript("togglePlay();", null)
                    showVideoInfoHud()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    showVideoInfoHud()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showUpNextPanel()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    toggleChannelSubscription()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    onBackPressed()
                    return true
                }
            }
        }

        val p = player ?: return super.onKeyDown(keyCode, event)

        // 早送り長押しで倍速切り替え
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && event?.isLongPress == true) {
            cyclePlaybackSpeed()
            return true
        }

        // 巻き戻し長押しで等速へリセット
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND && event?.isLongPress == true) {
            resetPlaybackSpeed()
            return true
        }

        when (keyCode) {
            // リモコンのメニューキー (MENU) でチャンネル登録/解除
            KeyEvent.KEYCODE_MENU -> {
                toggleChannelSubscription()
                return true
            }

            // D-Pad 下キーで関連動画（Up Next）表示
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showUpNextPanel()
                return true
            }

            // D-Pad 上キーで動画情報 & RYD HUD 表示 (表示中にもう一度押すと画質・字幕メニュー)
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (videoInfoHud.visibility == View.VISIBLE && videoInfoHud.alpha > 0.5f && currentStreamInfo != null) {
                    showPlayerOptionsMenu()
                } else {
                    showVideoInfoHud()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (p.isPlaying) {
                    p.pause()
                    showVideoInfoHud()
                } else {
                    p.play()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (p.isCurrentMediaItemLive) {
                    // ライブ配信時の巻き戻しはバッファ枯渇を防ぐため無効化または通知
                    Toast.makeText(this, "ライブ配信中はシークできません", Toast.LENGTH_SHORT).show()
                } else {
                    val newPos = (p.currentPosition - 10_000).coerceAtLeast(0)
                    p.seekTo(newPos)
                    showVideoInfoHud()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (p.isCurrentMediaItemLive) {
                    // ライブ配信時は最新のライブエッジ位置へ追っかけ同期
                    p.seekToDefaultPosition()
                    showVideoInfoHud()
                } else {
                    // duration 未確定 (C.TIME_UNSET = 負値) の状態で coerceAtMost すると先頭へ飛んでしまうため除外
                    val duration = p.duration
                    val target = p.currentPosition + 10_000
                    val newPos = if (duration != C.TIME_UNSET && duration > 0) target.coerceAtMost(duration) else target
                    p.seekTo(newPos)
                    showVideoInfoHud()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (p.isPlaying) {
                    p.pause()
                    showVideoInfoHud()
                } else {
                    p.play()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                onBackPressed()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun toggleChannelSubscription() {
        if (uploaderName.isBlank()) return
        val channelName = uploaderName
        // チャンネル ID / @handle が判明していればそれをキーにする (旧バージョンはチャンネル名をキーにしていた)
        val legacyKey = channelName
        val channelId = ChannelKey.fromUploaderUrl(uploaderUrl) ?: legacyKey
        lifecycleScope.launch {
            val dao = (application as FireTubeApp).database.videoDao()
            val isSub = dao.isSubscribed(channelId) || dao.isSubscribed(legacyKey)
            if (isSub) {
                dao.deleteSubscription(channelId)
                dao.deleteSubscription(legacyKey)
                Toast.makeText(this@PlaybackActivity, "「$channelName」の登録を解除しました", Toast.LENGTH_SHORT).show()
            } else {
                dao.insertSubscription(
                    SubscriptionEntity(
                        channelId = channelId,
                        channelName = channelName,
                        channelAvatarUrl = null
                    )
                )
                Toast.makeText(this@PlaybackActivity, "「$channelName」をチャンネル登録しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (upNextContainer.visibility == View.VISIBLE) {
            if (isPlaybackEnded) {
                cancelAutoplay()
                // 動画終了時のBACKは画面を閉じて戻る
            } else {
                hideUpNextPanel()
                return
            }
        }
        if (isTaskRoot) {
            startActivity(Intent(this, com.firetube.tv.ui.main.MainActivity::class.java))
        }
        super.onBackPressed()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            cyclePlaybackSpeed()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            resetPlaybackSpeed()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun cyclePlaybackSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedList.size
        val speed = speedList[currentSpeedIndex]
        applyPlaybackSpeed(speed)
    }

    private fun resetPlaybackSpeed() {
        currentSpeedIndex = 0
        applyPlaybackSpeed(1.0f)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        speedIndicator.text = "${getString(R.string.playback_speed)}: ${speed}x"
        speedIndicator.visibility = View.VISIBLE

        speedHudDismissJob?.cancel()
        speedHudDismissJob = lifecycleScope.launch {
            delay(1800)
            speedIndicator.visibility = View.GONE
        }
    }

    private fun saveHistoryIfStarted() {
        if (!hasPlaybackStarted) return
        saveHistory(player?.currentPosition ?: 0L)
    }

    private fun saveHistory(positionMs: Long) {
        if (videoId.isEmpty()) return
        val app = application as FireTubeApp
        val targetId = videoId
        val targetTitle = videoTitle
        val targetUploader = uploaderName
        val targetThumb = thumbnailUrl
        val targetUploaderUrl = uploaderUrl
        val dur = player?.duration ?: 0L
        val safeDurationSeconds = if (dur > 0L) dur / 1000L else 0L
        com.firetube.tv.data.local.WatchProgressStore.update(targetId, positionMs, safeDurationSeconds * 1000L)

        // Activity破棄後でも確実に保存を完了させるため NonCancellable で実行
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) {
                val db = app.database
                val entity = VideoHistoryEntity(
                    id = targetId,
                    title = targetTitle,
                    uploaderName = targetUploader,
                    thumbnailUrl = targetThumb,
                    durationSeconds = safeDurationSeconds,
                    lastPlayedPositionMs = positionMs,
                    uploaderUrl = targetUploaderUrl
                )
                db.videoDao().insertOrUpdateHistory(entity)
                db.videoDao().trimHistory()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelAutoplay()
    }

    override fun onStop() {
        super.onStop()
        cancelAutoplay()
        if (isUsingWebViewFallback && ::webViewPlayer.isInitialized) {
            webViewPlayer.evaluateJavascript("if (player && player.pauseVideo) { player.pauseVideo(); }", null)
        }
        saveHistoryIfStarted()
        if (!isChangingConfigurations) {
            player?.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        if (player != null && playerView.player == null) {
            playerView.player = player
        }
    }

    override fun onDestroy() {
        cancelAutoplay()
        loadStreamJob?.cancel()
        upNextJob?.cancel()
        sponsorJob?.cancel()
        rydJob?.cancel()
        sponsorMonitorJob?.cancel()
        speedHudDismissJob?.cancel()
        hudDismissJob?.cancel()
        bufferingWatchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        notificationDismissJob?.cancel()
        seekBarUpdateJob?.cancel()
        GoogleVideoDataSource.onStreamForbiddenListener = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        playerView.player = null
        player?.release()
        player = null
        if (::webViewPlayer.isInitialized) {
            webViewPlayer.loadUrl("about:blank")
            (webViewPlayer.parent as? android.view.ViewGroup)?.removeView(webViewPlayer)
            webViewPlayer.destroy()
        }
        super.onDestroy()
    }
}
