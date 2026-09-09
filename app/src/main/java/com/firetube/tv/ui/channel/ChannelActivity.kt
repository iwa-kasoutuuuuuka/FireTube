package com.firetube.tv.ui.channel

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * チャンネル動画グリッド表示用 Activity
 */
class ChannelActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)

        if (savedInstanceState == null) {
            val channelUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
            val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""

            val fragment = ChannelFragment.newInstance(channelUrl, channelName)
            supportFragmentManager.beginTransaction()
                .replace(R.id.channel_fragment_container, fragment)
                .commit()
        }
    }

    companion object {
        const val EXTRA_CHANNEL_URL = "extra_channel_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }
}
