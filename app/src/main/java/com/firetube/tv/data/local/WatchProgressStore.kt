package com.firetube.tv.data.local

/**
 * カードの「視聴済み位置バー」用のメモリ上の視聴進捗 (動画ID → 0.0〜1.0)
 * カードのバインド毎に DB を引かないよう、履歴読み込み時にまとめて更新する
 */
object WatchProgressStore {

    @Volatile
    private var progress: Map<String, Float> = emptyMap()

    /** 内容が変わるたびに増加 (表示側は前回描画時の値と比較して再バインド要否を判定) */
    @Volatile
    var version: Int = 0
        private set

    fun get(videoId: String): Float? = progress[videoId]

    /** @return 内容が変化した場合 true */
    suspend fun reload(dao: VideoDao): Boolean {
        val updated = dao.getAllHistory().mapNotNull { h ->
            toFraction(h.lastPlayedPositionMs, h.durationSeconds * 1000L)?.let { h.id to it }
        }.toMap()
        val changed = updated != progress
        if (changed) {
            progress = updated
            version++
        }
        return changed
    }

    fun update(videoId: String, positionMs: Long, durationMs: Long) {
        val fraction = toFraction(positionMs, durationMs)
        progress = if (fraction != null) progress + (videoId to fraction) else progress - videoId
        version++
    }

    fun remove(videoId: String) {
        progress = progress - videoId
        version++
    }

    fun clear() {
        progress = emptyMap()
        version++
    }

    private fun toFraction(positionMs: Long, durationMs: Long): Float? {
        if (durationMs <= 0 || positionMs < 5_000) return null
        // 終盤 (残り 5% 未満) は視聴完了としてバーを満たす
        val f = positionMs.toFloat() / durationMs
        return if (f >= 0.95f) 1f else f.coerceIn(0f, 1f)
    }
}
