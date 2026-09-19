package com.firetube.tv.ui.channel

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.firetube.tv.FireTubeApp
import com.firetube.tv.R
import com.firetube.tv.data.local.SubscriptionEntity
import kotlinx.coroutines.launch

/**
 * チャンネル動画グリッド表示用 Activity
 * ローカルチャンネル登録 (Room DB) のトグルに対応
 */
class ChannelActivity : FragmentActivity() {

    private lateinit var btnSubscribe: Button
    private var channelUrl: String = ""
    private var channelName: String = ""
    private var isCurrentlySubscribed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)

        channelUrl = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""

        btnSubscribe = findViewById(R.id.btn_subscribe)

        if (savedInstanceState == null) {
            val fragment = ChannelFragment.newInstance(channelUrl, channelName)
            supportFragmentManager.beginTransaction()
                .replace(R.id.channel_fragment_container, fragment)
                .commit()
        }

        setupSubscribeButton()
    }

    private fun setupSubscribeButton() {
        val channelId = extractChannelId(channelUrl)
        val db = (application as FireTubeApp).database

        lifecycleScope.launch {
            isCurrentlySubscribed = db.videoDao().isSubscribed(channelId)
            updateButtonState()
        }

        btnSubscribe.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnSubscribe.setTextColor(ContextCompat.getColor(this@ChannelActivity, R.color.background_dark))
                btnSubscribe.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@ChannelActivity, R.color.accent_focus)
                )
            } else {
                btnSubscribe.setTextColor(ContextCompat.getColor(this@ChannelActivity, android.R.color.white))
                updateButtonState()
            }
        }

        btnSubscribe.setOnClickListener {
            lifecycleScope.launch {
                if (isCurrentlySubscribed) {
                    db.videoDao().deleteSubscription(channelId)
                    isCurrentlySubscribed = false
                    Toast.makeText(this@ChannelActivity, R.string.unsubscribe, Toast.LENGTH_SHORT).show()
                } else {
                    val entity = SubscriptionEntity(
                        channelId = channelId,
                        channelName = channelName.ifEmpty { "チャンネル" },
                        channelAvatarUrl = null
                    )
                    db.videoDao().insertSubscription(entity)
                    isCurrentlySubscribed = true
                    Toast.makeText(this@ChannelActivity, R.string.subscribed, Toast.LENGTH_SHORT).show()
                }
                updateButtonState()
            }
        }
    }

    private fun updateButtonState() {
        if (isCurrentlySubscribed) {
            btnSubscribe.text = getString(R.string.subscribed)
            if (!btnSubscribe.isFocused) {
                btnSubscribe.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.card_unfocused)
                )
            }
        } else {
            btnSubscribe.text = getString(R.string.subscribe)
            if (!btnSubscribe.isFocused) {
                btnSubscribe.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary_red)
                )
            }
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && !btnSubscribe.isFocused) {
                val view = currentFocus
                if (view != null && btnSubscribe.visibility == android.view.View.VISIBLE) {
                    val next = view.focusSearch(android.view.View.FOCUS_UP)
                    // Leanback の TitleView やフォーカス不可 View が探索された場合、または上方向に進めない場合はボタンに遷移
                    if (next == null || next == view || !next.isFocusable || next.id == androidx.leanback.R.id.browse_title_group || next.id == R.id.channel_fragment_container) {
                        btnSubscribe.requestFocus()
                        return true
                    }
                }
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && btnSubscribe.isFocused) {
                val container = findViewById<android.view.View>(R.id.channel_fragment_container)
                container?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun extractChannelId(url: String): String {
        return url.substringAfterLast("/")
            .substringAfterLast("channel/")
            .ifEmpty { url }
    }

    companion object {
        const val EXTRA_CHANNEL_URL = "extra_channel_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }
}
