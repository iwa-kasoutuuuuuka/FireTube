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
    private var playlistId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelUrl = arguments?.getString(ARG_CHANNEL_URL) ?: ""
        playlistId = arguments?.getString(ARG_PLAYLIST_ID)
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: getString(R.string.channel_videos)

        title = channelName

        val gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = 4
        }
        setGridPresenter(gridPresenter)

        videoAdapter = ArrayObjectAdapter(VideoCardPresenter(onLongPress = { item ->
            com.firetube.tv.ui.common.VideoActionMenu.show(requireContext(), viewLifecycleOwner.lifecycleScope, item)
        }))
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
                val intent = PlaybackActivity.createIntent(requireContext(), item)
                if (playlistId != null) {
                    // 再生リストでは、選択した動画以降をキューとして渡し順番に連続再生する
                    val all = (0 until videoAdapter.size()).mapNotNull { videoAdapter.get(it) as? VideoItem }
                    val index = all.indexOfFirst { it.id == item.id }
                    if (index >= 0) {
                        intent.putExtra(PlaybackActivity.EXTRA_QUEUE, ArrayList(all.drop(index + 1)))
                    }
                }
                startActivity(intent)
            }
        }
    }

    private fun loadChannelVideos() {
        if (channelUrl.isEmpty() && playlistId == null) {
            Toast.makeText(requireContext(), R.string.error_loading, Toast.LENGTH_SHORT).show()
            return
        }

        progressBarManager.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val pl = playlistId
            val result = if (pl != null) {
                VideoRepository.getPlaylistVideos(pl)
            } else {
                VideoRepository.getChannelVideos(channelUrl, channelName)
            }
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
        const val ARG_PLAYLIST_ID = "arg_playlist_id"

        fun newInstance(channelUrl: String, channelName: String, playlistId: String? = null): ChannelFragment {
            return ChannelFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHANNEL_URL, channelUrl)
                    putString(ARG_CHANNEL_NAME, channelName)
                    putString(ARG_PLAYLIST_ID, playlistId)
                }
            }
        }
    }
}
