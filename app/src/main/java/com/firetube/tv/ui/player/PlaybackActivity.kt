package com.firetube.tv.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.extractor.SponsorBlockService
import com.firetube.tv.data.local.SubscriptionEntity
import com.firetube.tv.data.local.VideoHistoryEntity
import com.firetube.tv.data.model.SponsorSegment
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.network.NetworkClient
import com.firetube.tv.data.network.ReturnYouTubeDislikeClient
import com.firetube.tv.data.repository.VideoRepository
import com.firetube.tv.ui.main.VideoCardPresenter
import com.firetube.tv.util.AppPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private lateinit var playerView: PlayerView
    private lateinit var loadingView: ProgressBar
    private lateinit var sponsorBanner: View
    private lateinit var speedIndicator: TextView
    private lateinit var upNextContainer: LinearLayout
    private lateinit var upNextGrid: HorizontalGridView

    private lateinit var videoInfoHud: View
    private lateinit var videoHudTitle: TextView
    private lateinit var videoHudChannel: TextView
    private lateinit var videoHudRyd: TextView
    private var hudDismissJob: Job? = null

    private val upNextAdapter = ArrayObjectAdapter(VideoCardPresenter())

    private var videoId: String = ""
    private var videoTitle: String = ""
    private var uploaderName: String = ""
    private var thumbnailUrl: String = ""

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorMonitorJob: Job? = null
    private var speedHudDismissJob: Job? = null

    private val speedList = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
        uploaderName = intent.getStringExtra(EXTRA_UPLOADER_NAME) ?: ""
        thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""

        playerView = findViewById(R.id.player_view)
        loadingView = findViewById(R.id.player_loading)
        sponsorBanner = findViewById(R.id.sponsor_banner)
        speedIndicator = findViewById(R.id.speed_indicator)
        upNextContainer = findViewById(R.id.up_next_container)
        upNextGrid = findViewById(R.id.up_next_grid)

        videoInfoHud = findViewById(R.id.video_info_hud)
        videoHudTitle = findViewById(R.id.video_hud_title)
        videoHudChannel = findViewById(R.id.video_hud_channel)
        videoHudRyd = findViewById(R.id.video_hud_ryd)

        if (videoId.isEmpty()) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUpNextGrid()
        initPlayer()
        loadStreamAndPlay()
        loadSponsorBlock()
        loadRydVotes()
        loadUpNextVideos()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newVideoId = intent?.getStringExtra(EXTRA_VIDEO_ID) ?: return
        if (newVideoId.isNotEmpty() && newVideoId != videoId) {
            saveHistory(player?.currentPosition ?: 0)
            player?.stop()
            loadingView.visibility = View.VISIBLE
            videoId = newVideoId
            videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: ""
            uploaderName = intent.getStringExtra(EXTRA_UPLOADER_NAME) ?: ""
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL) ?: ""
            loadStreamAndPlay()
            loadSponsorBlock()
            loadRydVotes()
            loadUpNextVideos()
        }
    }

    private fun setupUpNextGrid() {
        val bridgeAdapter = ItemBridgeAdapter(upNextAdapter)
        upNextGrid.adapter = bridgeAdapter
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val item = upNextAdapter.get(viewHolder.adapterPosition) as? VideoItem
                    if (item != null) {
                        switchVideo(item)
                    }
                }
            }
        })
    }

    private fun initPlayer() {
        val pref = AppPreferences.getInstance(this)

        val trackSelector = DefaultTrackSelector(this).apply {
            if (pref.preferAvcCodec) {
                parameters = buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(NetworkClient.client)
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(PlayerLoadControlFactory.createLowRamLoadControl(pref.bufferProfile))
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.i(TAG, "onPlaybackStateChanged: $state (BUFFERING=2, READY=3, ENDED=4, IDLE=1)")
                        when (state) {
                            Player.STATE_BUFFERING -> loadingView.visibility = View.VISIBLE
                            Player.STATE_READY -> {
                                Log.i(TAG, "Playback ready! Hiding loadingView and showing HUD")
                                loadingView.visibility = View.GONE
                                showVideoInfoHud()
                            }
                            Player.STATE_ENDED -> {
                                saveHistory(currentPosition)
                                showUpNextPanel()
                            }
                            Player.STATE_IDLE -> Unit
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                        loadingView.visibility = View.GONE
                        Toast.makeText(this@PlaybackActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
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

        lifecycleScope.launch {
            val result = ReturnYouTubeDislikeClient.getVotes(videoId)
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
        lifecycleScope.launch {
            val result = VideoRepository.extractStreamInfo(videoId)
            result.onSuccess { streamInfo ->
                startPlayback(streamInfo)
            }.onFailure { e ->
                Log.e(TAG, "Stream extraction error", e)
                loadingView.visibility = View.GONE
                Toast.makeText(this@PlaybackActivity, R.string.error_loading, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPlayback(streamInfo: StreamInfoData) {
        val pref = AppPreferences.getInstance(this)
        val targetQuality = pref.defaultQuality

        Log.i(TAG, "startPlayback: streamInfo has ${streamInfo.videoStreams.size} video streams, hlsUrl=${streamInfo.hlsUrl}")
        streamInfo.videoStreams.forEachIndexed { i, s ->
            Log.d(TAG, "Stream[$i]: res=${s.resolution}, fmt=${s.format}, videoOnly=${s.isVideoOnly}, url=${s.url.take(60)}")
        }

        // 設定画質に適合するストリームを検索（AVC優先時は MP4 / H.264 を最優先）
        val matchingStreams = streamInfo.videoStreams.filter { !it.isVideoOnly }
        val qualityFiltered = matchingStreams.filter { it.resolution.contains(targetQuality, ignoreCase = true) }
        val candidates = if (qualityFiltered.isNotEmpty()) qualityFiltered else matchingStreams

        val preferredStream = if (pref.preferAvcCodec) {
            candidates.firstOrNull { it.format.equals("mp4", ignoreCase = true) || it.url.contains("mime=video%2Fmp4") }
                ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }

        // HLS (アダプティブビットレート) を最優先。なければ設定画質の MP4 / 単一ストリームへフォールバック
        val streamUrl = streamInfo.hlsUrl
            ?: preferredStream?.url
            ?: streamInfo.videoStreams.firstOrNull { !it.isVideoOnly }?.url
            ?: streamInfo.videoStreams.firstOrNull()?.url

        Log.i(TAG, "Selected streamUrl (HLS=${streamUrl == streamInfo.hlsUrl}): ${streamUrl?.take(100)}")

        if (streamUrl == null) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val mediaItem = if (streamUrl.contains(".m3u8") || streamUrl == streamInfo.hlsUrl) {
            MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            MediaItem.fromUri(streamUrl)
        }

        lifecycleScope.launch {
            val db = (application as FireTubeApp).database
            val lastPos = db.videoDao().getLastPosition(videoId)
            val durationMs = streamInfo.durationSeconds * 1000L
            Log.i(TAG, "Restoring position for $videoId: lastPos=$lastPos, durationMs=$durationMs")
            player?.let { p ->
                p.setMediaItem(mediaItem)
                val shouldSeek = lastPos != null && lastPos > 5000 && (durationMs <= 0 || lastPos < durationMs - 10000)
                if (shouldSeek) {
                    Log.i(TAG, "Seeking to saved position: $lastPos ms")
                    p.seekTo(lastPos!!)
                } else {
                    p.seekTo(0)
                }
                p.prepare()
                p.play()
                startSponsorMonitor()
            }
        }
    }

    private fun loadSponsorBlock() {
        lifecycleScope.launch {
            sponsorSegments = SponsorBlockService.getSkipSegments(videoId)
        }
    }

    private fun loadUpNextVideos() {
        // 再生開始直後のWi-Fi帯域とCPUをストリーム取得に集中させるため、関連動画は2.5秒遅延取得
        lifecycleScope.launch {
            delay(2500)
            if (!isActive) return@launch
            val result = VideoRepository.getUpNextVideos(videoId)
            result.onSuccess { videos ->
                upNextAdapter.clear()
                upNextAdapter.addAll(0, videos)
            }
        }
    }

    private fun switchVideo(item: VideoItem) {
        saveHistory(player?.currentPosition ?: 0)
        player?.stop()
        loadingView.visibility = View.VISIBLE
        hideUpNextPanel()

        videoId = item.id
        videoTitle = item.title
        uploaderName = item.uploaderName
        thumbnailUrl = item.thumbnailUrl

        loadStreamAndPlay()
        loadSponsorBlock()
        loadRydVotes()
        loadUpNextVideos()
    }

    private fun showUpNextPanel() {
        if (upNextAdapter.size() == 0) return
        upNextContainer.visibility = View.VISIBLE
        upNextGrid.requestFocus()
    }

    private fun hideUpNextPanel() {
        upNextContainer.visibility = View.GONE
        playerView.requestFocus()
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
        val p = player ?: return super.onKeyDown(keyCode, event)

        // Up Next パネル表示中のキー処理
        if (upNextContainer.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP -> {
                    hideUpNextPanel()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

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
                val newPos = (p.currentPosition - 10_000).coerceAtLeast(0)
                p.seekTo(newPos)
                showVideoInfoHud()
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val newPos = (p.currentPosition + 10_000).coerceAtMost(p.duration)
                p.seekTo(newPos)
                showVideoInfoHud()
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

    override fun onBackPressed() {
        if (upNextContainer.visibility == View.VISIBLE) {
            hideUpNextPanel()
            return
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
        lifecycleScope.launch {
            val db = (application as FireTubeApp).database
            val dur = player?.duration ?: 0L
            val safeDurationSeconds = if (dur > 0L) dur / 1000L else 0L
            val entity = VideoHistoryEntity(
                id = videoId,
                title = videoTitle,
                uploaderName = uploaderName,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = safeDurationSeconds,
                lastPlayedPositionMs = positionMs
            )
            db.videoDao().insertOrUpdateHistory(entity)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            saveHistory(p.currentPosition)
        }
        playerView.player = null
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
        sponsorMonitorJob?.cancel()
        speedHudDismissJob?.cancel()
        hudDismissJob?.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
