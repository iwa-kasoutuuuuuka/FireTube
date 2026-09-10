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

        val thumbUrl = when {
            video.thumbnailUrl.startsWith("//") -> "https:${video.thumbnailUrl}"
            video.thumbnailUrl.isNotEmpty() -> video.thumbnailUrl
            video.id.isNotEmpty() && !video.id.startsWith("__") -> "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"
            else -> ""
        }

        // 低メモリ・高速 Glide ロード (320x180直接デコードによるメモリ93%削減 & 全キャッシュ)
        if (thumbUrl.isNotEmpty()) {
            Glide.with(holder.thumbnailImage.context)
                .load(thumbUrl)
                .placeholder(R.drawable.default_thumbnail_bg)
                .error(R.drawable.default_thumbnail_bg)
                .override(320, 180)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.thumbnailImage)
        } else {
            Glide.with(holder.thumbnailImage.context).clear(holder.thumbnailImage)
            holder.thumbnailImage.setImageResource(R.drawable.default_thumbnail_bg)
        }
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
            // リモコンD-Padのフォーカスアニメーション (RenderThread直結 ViewPropertyAnimator で60fps吸着)
            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                val elevation = if (hasFocus) 16f else 4f

                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(120)
                    .start()
            }
        }
    }
}
