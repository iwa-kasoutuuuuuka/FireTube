package com.firetube.tv.ui.main

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.firetube.tv.R
import com.firetube.tv.data.model.VideoItem

/**
 * Fire TV 物理リモコン（D-Pad）対応のカード表示 Presenter
 * - フォーカス時に枠線ハイライト + スムーズな拡大アニメーション（吸着UX）
 * - Glide によるメモリ極小サムネイル読み込み
 */
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_video_card, parent, false)
        return VideoCardViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as? VideoItem ?: return
        val holder = viewHolder as VideoCardViewHolder

        holder.titleText.text = video.title
        holder.uploaderText.text = video.uploaderName
        holder.durationText.text = video.formattedDuration
        holder.durationText.visibility = if (video.formattedDuration.isNotEmpty()) View.VISIBLE else View.GONE

        // 低メモリ Glide ロード
        Glide.with(holder.thumbnailImage.context)
            .load(video.thumbnailUrl)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(holder.thumbnailImage)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val holder = viewHolder as VideoCardViewHolder
        Glide.with(holder.thumbnailImage.context).clear(holder.thumbnailImage)
    }

    inner class VideoCardViewHolder(view: View) : ViewHolder(view) {
        val cardRoot: CardView = view.findViewById(R.id.card_root)
        val thumbnailImage: ImageView = view.findViewById(R.id.thumbnail_image)
        val durationText: TextView = view.findViewById(R.id.duration_badge)
        val titleText: TextView = view.findViewById(R.id.video_title)
        val uploaderText: TextView = view.findViewById(R.id.uploader_name)

        init {
            // リモコンD-Padのフォーカスアニメーション (吸着拡大エフェクト)
            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                val elevation = if (hasFocus) 16f else 4f

                val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, scale)
                val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)
                val translationZ = PropertyValuesHolder.ofFloat(View.TRANSLATION_Z, elevation)

                ObjectAnimator.ofPropertyValuesHolder(v, scaleX, scaleY, translationZ).apply {
                    duration = 150
                    start()
                }
            }
        }
    }
}
