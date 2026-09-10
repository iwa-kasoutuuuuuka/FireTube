package com.firetube.tv.ui.main

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.firetube.tv.R
import com.firetube.tv.data.model.VideoItem

/**
 * Fire TV 物理リモコン（D-Pad）対応のカード表示 Presenter
 * - フォーカス時に枠線ハイライト + スムーズな拡大アニメーション（吸着UX）
 * - Glide によるメモリ極小サムネイル読み込み
 * - YouTube 公式 CDN 直結 & 二重フォールバックによる 100% 途切れないサムネイル表示
 */
class VideoCardPresenter : Presenter() {

    companion object {
        private const val TAG = "VideoCardPresenter"
    }

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

        val isStandardVideo = video.id.isNotEmpty() && !video.id.startsWith("__")
        val ytHqUrl = if (isStandardVideo) "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg" else ""
        val ytMqUrl = if (isStandardVideo) "https://i.ytimg.com/vi/${video.id}/mqdefault.jpg" else ""

        val rawThumb = when {
            video.thumbnailUrl.startsWith("//") -> "https:${video.thumbnailUrl}"
            video.thumbnailUrl.isNotEmpty() -> video.thumbnailUrl
            else -> ""
        }

        // Pipedの不安定なプロキシURLやsqp付き404リスクURLを回避し、
        // 全動画に100%恒久的に存在する公式CDNのhqdefault.jpgを最優先
        val primaryUrl = when {
            isStandardVideo -> ytHqUrl
            rawThumb.isNotEmpty() -> rawThumb
            else -> ""
        }

        val secondaryUrl = when {
            isStandardVideo && primaryUrl != ytMqUrl -> ytMqUrl
            rawThumb.isNotEmpty() && rawThumb != primaryUrl -> rawThumb
            else -> ""
        }

        if (primaryUrl.isNotEmpty()) {
            val fallbackRequest = if (secondaryUrl.isNotEmpty()) {
                Glide.with(holder.thumbnailImage.context)
                    .load(secondaryUrl)
                    .override(320, 180)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
            } else {
                null
            }

            var request = Glide.with(holder.thumbnailImage.context)
                .load(primaryUrl)
                .placeholder(R.drawable.default_thumbnail_bg)
                .override(320, 180)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.w(TAG, "Thumbnail primary load failed for $model, switching to fallback: ${e?.message}")
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean = false
                })

            if (fallbackRequest != null) {
                request = request.error(fallbackRequest)
            } else {
                request = request.error(R.drawable.default_thumbnail_bg)
            }

            request.into(holder.thumbnailImage)
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
