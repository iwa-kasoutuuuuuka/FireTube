package com.firetube.tv.ui.channel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import com.firetube.tv.R
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.data.repository.VideoRepository
import com.firetube.tv.ui.main.VideoCardPresenter
import com.firetube.tv.ui.player.PlaybackActivity
import kotlinx.coroutines.launch

/**
 * チャンネル詳細画面 (Leanback VerticalGridSupportFragment)
 * チャンネル内の最新動画を4列グリッドで表示
 */
class ChannelFragment : VerticalGridSupportFragment() {

    private lateinit var videoAdapter: ArrayObjectAdapter
    private var channelUrl: String = ""
    private var channelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelUrl = arguments?.getString(ARG_CHANNEL_URL) ?: ""
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: getString(R.string.channel_videos)

        title = channelName

        val gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = 4
        }
        setGridPresenter(gridPresenter)

        videoAdapter = ArrayObjectAdapter(VideoCardPresenter())
        adapter = videoAdapter

        setupEventListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadChannelVideos()
    }

    private fun setupEventListeners() {
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

    private fun loadChannelVideos() {
        if (channelUrl.isEmpty()) {
            Toast.makeText(requireContext(), R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }

        progressBarManager.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = VideoRepository.getChannelVideos(channelUrl)
            progressBarManager.hide()
            result.onSuccess { videos ->
                videoAdapter.clear()
                videoAdapter.addAll(0, videos)
            }.onFailure {
                Toast.makeText(requireContext(), R.string.network_error_msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ARG_CHANNEL_URL = "arg_channel_url"
        const val ARG_CHANNEL_NAME = "arg_channel_name"

        fun newInstance(channelUrl: String, channelName: String): ChannelFragment {
            return ChannelFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHANNEL_URL, channelUrl)
                    putString(ARG_CHANNEL_NAME, channelName)
                }
            }
        }
    }
}
