package com.firetube.tv.ui.common

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.firetube.tv.FireTubeApp
import com.firetube.tv.data.local.WatchLaterEntity
import com.firetube.tv.data.local.WatchProgressStore
import com.firetube.tv.data.model.ChannelKey
import com.firetube.tv.data.model.VideoItem
import com.firetube.tv.ui.channel.ChannelActivity
import kotlinx.coroutines.launch

/**
 * 動画カード長押し (決定キー長押し) 時の操作メニュー
 * - 後で見るに追加 / 削除
 * - 視聴履歴から削除
 * - チャンネルを開く
 */
object VideoActionMenu {

    /**
     * @param onChanged 後で見る・履歴が変更された後に呼ばれる (一覧の再読み込み用)
     */
    fun show(context: Context, scope: LifecycleCoroutineScope, item: VideoItem, onChanged: () -> Unit = {}) {
        if (item.id.startsWith("__")) return
        val dao = (context.applicationContext as FireTubeApp).database.videoDao()

        scope.launch {
            val inWatchLater = dao.isInWatchLater(item.id)
            val inHistory = dao.getLastPosition(item.id) != null
            val channelKey = ChannelKey.fromUploaderUrl(item.uploaderUrl)

            val labels = mutableListOf<String>()
            val actions = mutableListOf<suspend () -> Unit>()

            labels.add(if (inWatchLater) "「後で見る」から削除" else "「後で見る」に追加")
            actions.add {
                if (inWatchLater) {
                    dao.deleteWatchLater(item.id)
                    toast(context, "「後で見る」から削除しました")
                } else {
                    dao.insertWatchLater(
                        WatchLaterEntity(
                            id = item.id,
                            title = item.title,
                            uploaderName = item.uploaderName,
                            uploaderUrl = item.uploaderUrl,
                            thumbnailUrl = item.thumbnailUrl,
                            durationSeconds = item.durationSeconds
                        )
                    )
                    toast(context, "「後で見る」に追加しました")
                }
                onChanged()
            }

            if (inHistory) {
                labels.add("視聴履歴から削除")
                actions.add {
                    dao.deleteHistory(item.id)
                    WatchProgressStore.remove(item.id)
                    toast(context, "視聴履歴から削除しました")
                    onChanged()
                }
            }

            if (channelKey != null) {
                labels.add("チャンネル「${item.uploaderName}」を開く")
                actions.add {
                    context.startActivity(Intent(context, ChannelActivity::class.java).apply {
                        putExtra(ChannelActivity.EXTRA_CHANNEL_URL, channelKey)
                        putExtra(ChannelActivity.EXTRA_CHANNEL_NAME, item.uploaderName)
                    })
                }
            }

            AlertDialog.Builder(context)
                .setTitle(item.title)
                .setItems(labels.toTypedArray()) { _, which ->
                    scope.launch { actions[which]() }
                }
                .show()
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
