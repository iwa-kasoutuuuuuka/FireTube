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
        private const val TAG = "PlaybackActivity"
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

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorMonitorJob: Job? = null
    private var speedHudDismissJob: Job? = null
    private var loadStreamJob: Job? = null
    private var upNextJob: Job? = null
    private var sponsorJob: Job? = null
    private var rydJob: Job? = null

    private val speedList = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        uploaderName = intent.getStringExtra(EXTRA_UPLOADER_NAME) ?: ""
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""

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
        startNewVideoSession(videoId, videoTitle, uploaderName, thumbnailUrl)
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
            startNewVideoSession(newVideoId, newTitle, newUploader, newThumb)
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
        thumb: String
    ) {
        // 1. 前回の再生履歴を非同期保存
        saveHistory(player?.currentPosition ?: 0)

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

        loadingView.visibility = View.VISIBLE

        // 6. 各種取得処理を起動
        loadStreamAndPlay()
        loadSponsorBlock()
        loadRydVotes()
        loadUpNextVideos()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewPlayer() {
        WebView.setWebContentsDebuggingEnabled(true)
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
                            Log.w(TAG, "Playback stalled for 3s at pos=${curPos}ms! Seamlessly switching to WebView fallback.")
                            switchToIframeFallback(curPos)
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
            parameters = paramsBuilder.build()
        }

        // YouTube CDN からの 403 Forbidden（1MB制限）を即時キャッチしてフォールバック
        GoogleVideoDataSource.onStreamForbiddenListener = { _, _ ->
            runOnUiThread {
                if (!isUsingWebViewFallback && !isFinishing && !isDestroyed) {
                    val currentPos = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
                    Log.w(TAG, "GoogleVideoDataSource 403 Forbidden received! Seamlessly switching to WebView fallback at pos=${currentPos}ms.")
                    switchToIframeFallback(currentPos)
                }
            }
        }

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(NetworkClient.client)
        val googleVideoDataSourceFactory = GoogleVideoDataSource.Factory(NetworkClient.client, okHttpDataSourceFactory)
        val cachedDataSourceFactory = ExoPlayerCacheManager.createCacheDataSourceFactory(this, googleVideoDataSourceFactory)

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
                                            Log.w(TAG, "Playback stalled mid-stream at pos=${currentPos}ms. Seamlessly switching to WebView IFrame fallback.")
                                            switchToIframeFallback(currentPos)
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
                            Log.w(TAG, "Player error encountered mid-playback. Seamlessly switching to WebView IFrame fallback at pos=${pos}ms.")
                            switchToIframeFallback(pos)
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

        // 設定されたデフォルト速度を適用
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

        hudDismissJob?.cancel()
        hudDismissJob = lifecycleScope.launch {
            delay(4000)
            videoInfoHud.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    videoInfoHud.visibility = View.GONE
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
                        errMsg.contains("OFFLINE") ||
                        errMsg.contains("UNPLAYABLE")

                if (isUpcomingOrOffline) {
                    val noticeText = if (errMsg.contains("プレミア") || errMsg.contains("開始予定") || errMsg.contains("OFFLINE")) {
                        errMsg
                    } else {
                        "この動画は現在プレミア公開前または配信準備中です"
                    }
                    showStatusNotification(noticeText)
                    Toast.makeText(this@PlaybackActivity, noticeText, Toast.LENGTH_LONG).show()
                }

                if (!isUsingWebViewFallback && !isFinishing && !isDestroyed) {
                    Log.w(TAG, "Stream extraction failed for $currentTargetId. Auto-recovering via WebView IFrame fallback.")
                    switchToIframeFallback(0L)
                } else {
                    loadingView.visibility = View.GONE
                    val errorMsg = when {
                        isUpcomingOrOffline -> errMsg
                        errMsg.contains("network", ignoreCase = true) -> getString(R.string.network_error_msg)
                        else -> getString(R.string.error_loading)
                    }
                    Toast.makeText(this@PlaybackActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPlayback(streamInfo: StreamInfoData) {
        currentStreamInfo = streamInfo
        val pref = AppPreferences.getInstance(this)
        val targetQuality = pref.defaultQuality

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
            executePlayback(mediaItem = mediaItem, durationMs = durationMs)
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
            executePlayback(mediaSource = mergedSource, durationMs = durationMs)
        } else if (bestMuxed != null) {
            Log.i(TAG, "Playing via Muxed stream: ${bestMuxed.resolution} (${bestMuxed.format})")
            val mediaItem = MediaItem.fromUri(bestMuxed.url)
            executePlayback(mediaItem = mediaItem, durationMs = durationMs)
        } else if (bestVideo != null) {
            Log.w(TAG, "Playing video-only stream (audio stream missing): ${bestVideo.resolution}")
            val mediaItem = MediaItem.fromUri(bestVideo.url)
            executePlayback(mediaItem = mediaItem, durationMs = durationMs)
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

    private fun executePlayback(mediaItem: MediaItem? = null, mediaSource: MediaSource? = null, durationMs: Long) {
        val currentTargetId = videoId
        lifecycleScope.launch {
            val db = (application as FireTubeApp).database
            val lastPos = db.videoDao().getLastPosition(currentTargetId)
            Log.i(TAG, "Restoring position for $currentTargetId: lastPos=$lastPos, durationMs=$durationMs")
            if (!isActive || videoId != currentTargetId) return@launch

            player?.let { p ->
                if (playerView.player == null) {
                    playerView.player = p
                }
                p.clearMediaItems()
                if (mediaSource != null) {
                    p.setMediaSource(mediaSource, /* resetPosition = */ true)
                } else if (mediaItem != null) {
                    p.setMediaItem(mediaItem, /* resetPosition = */ true)
                }
                val shouldSeek = lastPos != null && lastPos > 5000 && (durationMs <= 0 || lastPos < durationMs - 10000)
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

    private fun switchVideo(item: VideoItem) {
        startNewVideoSession(item.id, item.title, item.uploaderName, item.thumbnailUrl)
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
                                else -> true
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

            // D-Pad 上キーで動画情報 & RYD HUD 表示
            KeyEvent.KEYCODE_DPAD_UP -> {
                showVideoInfoHud()
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
                    val newPos = (p.currentPosition + 10_000).coerceAtMost(p.duration)
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
        lifecycleScope.launch {
            val db = (application as FireTubeApp).database
            val channelId = uploaderName
            val isSub = db.videoDao().isSubscribed(channelId)
            if (isSub) {
                db.videoDao().deleteSubscription(channelId)
                Toast.makeText(this@PlaybackActivity, "「$uploaderName」の登録を解除しました", Toast.LENGTH_SHORT).show()
            } else {
                db.videoDao().insertSubscription(
                    SubscriptionEntity(
                        channelId = channelId,
                        channelName = uploaderName,
                        channelAvatarUrl = null
                    )
                )
                Toast.makeText(this@PlaybackActivity, "「$uploaderName」をチャンネル登録しました", Toast.LENGTH_SHORT).show()
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

    private fun saveHistory(positionMs: Long) {
        if (videoId.isEmpty()) return
        val app = application as FireTubeApp
        val targetId = videoId
        val targetTitle = videoTitle
        val targetUploader = uploaderName
        val targetThumb = thumbnailUrl
        val dur = player?.duration ?: 0L
        val safeDurationSeconds = if (dur > 0L) dur / 1000L else 0L

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
                    lastPlayedPositionMs = positionMs
                )
                db.videoDao().insertOrUpdateHistory(entity)
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
        player?.let { p ->
            saveHistory(p.currentPosition)
        }
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
