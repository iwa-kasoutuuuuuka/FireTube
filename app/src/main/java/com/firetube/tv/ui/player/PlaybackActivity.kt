package com.firetube.tv.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.extractor.SponsorBlockService
import com.firetube.tv.data.extractor.YouTubeStreamExtractor
import com.firetube.tv.data.local.VideoHistoryEntity
import com.firetube.tv.data.model.SponsorSegment
import com.firetube.tv.data.model.StreamInfoData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fire TV 物理リモコン操作対応 高速ネイティブ動画プレイヤー
 * - 広告完全自動フリー（生ストリーム直再生）
 * - SponsorBlock による案件・OP/ED 自動ミリ秒スキップ
 * - 早送り長押しでのスマート倍速切り替え (1.0x -> 1.25x -> 1.5x -> 2.0x)
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

    private var videoId: String = ""
    private var videoTitle: String = ""
    private var uploaderName: String = ""
    private var thumbnailUrl: String = ""

    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorMonitorJob: Job? = null
    private var speedHudDismissJob: Job? = null

    // 再生速度リスト
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

        if (videoId.isEmpty()) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initPlayer()
        loadStreamAndPlay()
        loadSponsorBlock()
    }

    private fun initPlayer() {
        // 低メモリバッファLoadControlを適用してExoPlayerを生成
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
                            Player.STATE_ENDED -> saveHistory(currentPosition)
                            Player.STATE_IDLE -> Unit
                        }
                    }
                })
            }
        playerView.player = player
    }

    private fun loadStreamAndPlay() {
        loadingView.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YouTubeStreamExtractor.extractStreamInfo(videoId)
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
        val streamUrl = streamInfo.hlsUrl
            ?: streamInfo.videoStreams.firstOrNull { !it.isVideoOnly }?.url
            ?: streamInfo.videoStreams.firstOrNull()?.url

        if (streamUrl == null) {
            Toast.makeText(this, R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.let { p ->
            p.setMediaItem(mediaItem)
            p.prepare()

            // 以前の再生位置を復元
            lifecycleScope.launch {
                val db = (application as FireTubeApp).database
                val lastPos = db.videoDao().getLastPosition(videoId)
                if (lastPos != null && lastPos > 5000) {
                    p.seekTo(lastPos)
                }
            }

            // SponsorBlock 監視ループ開始
            startSponsorMonitor()
        }
    }

    private fun loadSponsorBlock() {
        lifecycleScope.launch {
            sponsorSegments = SponsorBlockService.getSkipSegments(videoId)
        }
    }

    /**
     * SponsorBlock 自動スキップループ
     * 100msごとに再生位置を判定し、区間に入ったら瞬時にスキップ
     */
    private fun startSponsorMonitor() {
        sponsorMonitorJob?.cancel()
        sponsorMonitorJob = lifecycleScope.launch {
            while (isActive) {
                player?.let { p ->
                    if (p.isPlaying && sponsorSegments.isNotEmpty()) {
                        val currentMs = p.currentPosition
                        val segmentToSkip = sponsorSegments.firstOrNull { it.contains(currentMs) }
                        if (segmentToSkip != null) {
                            Log.i(TAG, "SponsorBlock match: Skipping from ${segmentToSkip.startMs}ms to ${segmentToSkip.endMs}ms (${segmentToSkip.category})")
                            p.seekTo(segmentToSkip.endMs + 100)
                            showSponsorBanner()
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

    /**
     * リモコン物理キーのハンドリング（D-Pad、早送り・巻き戻しショートカット）
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)

        // 早送りキー長押しで再生速度切り替え
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && event?.isLongPress == true) {
            cyclePlaybackSpeed()
            return true
        }

        // 巻き戻しキー長押しで等速（1.0x）へリセット
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND && event?.isLongPress == true) {
            resetPlaybackSpeed()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }

            // D-Pad 左右 / 早送り・巻き戻し単押しで10秒シーク
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
        }

        return super.onKeyDown(keyCode, event)
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
        // 視聴位置をローカルDBに保存
        player?.let { p ->
            saveHistory(p.currentPosition)
        }

        // 低RAM対策: 画面非表示時は動画描画Surfaceを解除し、メモリとGPUリソースを解放
        playerView.player = null
        if (!isChangingConfigurations) {
            player?.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        // 復帰時にSurfaceを再バインド
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
