package com.firetube.tv.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.extractor.SponsorBlockService
import com.firetube.tv.data.local.VideoHistoryEntity
import com.firetube.tv.data.model.SponsorSegment
import com.firetube.tv.data.model.StreamInfoData
import com.firetube.tv.data.model.VideoItem
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
    private lateinit var playerView: PlayerView
    private lateinit var loadingView: ProgressBar
    private lateinit var sponsorBanner: View
    private lateinit var speedIndicator: TextView
    private lateinit var upNextContainer: LinearLayout
    private lateinit var upNextGrid: HorizontalGridView

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

        if (videoId.isEmpty()) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUpNextGrid()
        initPlayer()
        loadStreamAndPlay()
        loadSponsorBlock()
        loadUpNextVideos()
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
        player = ExoPlayer.Builder(this)
            .setLoadControl(PlayerLoadControlFactory.createLowRamLoadControl())
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> loadingView.visibility = View.VISIBLE
                            Player.STATE_READY -> loadingView.visibility = View.GONE
                            Player.STATE_ENDED -> {
                                saveHistory(currentPosition)
                                showUpNextPanel()
                            }
                            Player.STATE_IDLE -> Unit
                        }
                    }
                })
            }
        playerView.player = player

        // 設定されたデフォルト速度を適用
        val pref = AppPreferences.getInstance(this)
        applyPlaybackSpeed(pref.defaultSpeed)
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

        // 設定画質に最も適合するストリームを検索
        val preferredStream = streamInfo.videoStreams.firstOrNull {
            it.resolution.contains(targetQuality, ignoreCase = true) && !it.isVideoOnly
        }

        val streamUrl = preferredStream?.url
            ?: streamInfo.hlsUrl
            ?: streamInfo.videoStreams.firstOrNull { !it.isVideoOnly }?.url
            ?: streamInfo.videoStreams.firstOrNull()?.url

        if (streamUrl == null) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val mediaItem = MediaItem.fromUri(streamUrl)
        lifecycleScope.launch {
            val db = (application as FireTubeApp).database
            val lastPos = db.videoDao().getLastPosition(videoId)
            player?.let { p ->
                p.setMediaItem(mediaItem)
                // 前回位置がある場合は prepare 前にシークして初期バッファリングの二重走りを防止
                if (lastPos != null && lastPos > 5000) {
                    p.seekTo(lastPos)
                }
                p.prepare()
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
        hideUpNextPanel()

        videoId = item.id
        videoTitle = item.title
        uploaderName = item.uploaderName
        thumbnailUrl = item.thumbnailUrl

        loadStreamAndPlay()
        loadSponsorBlock()
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
            // D-Pad 下キーで関連動画（Up Next）表示
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showUpNextPanel()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val newPos = (p.currentPosition - 10_000).coerceAtLeast(0)
                p.seekTo(newPos)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val newPos = (p.currentPosition + 10_000).coerceAtMost(p.duration)
                p.seekTo(newPos)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                onBackPressed()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
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
            val entity = VideoHistoryEntity(
                id = videoId,
                title = videoTitle,
                uploaderName = uploaderName,
                thumbnailUrl = thumbnailUrl,
                durationSeconds = (player?.duration ?: 0) / 1000,
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
        player?.release()
        player = null
        super.onDestroy()
    }
}
