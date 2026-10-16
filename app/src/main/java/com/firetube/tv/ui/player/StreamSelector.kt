package com.firetube.tv.ui.player

import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.VideoStream

/**
 * DASH / Muxed ストリームの選択ロジック (純粋関数・ユニットテスト対象)
 *
 * 旧実装 (PlaybackActivity 内) の問題:
 * - 画質文字列を固定の when で判定しており "360p" が "720" 扱いになっていた
 * - コンテナ (mp4) で AVC を判定しており、同じ mp4 コンテナの AV1 (av01) も AVC と誤判定していた
 * - 目的の画質が無い場合に全ストリームから最大ビットレートを選び、HD 端末で 2160p を掴み得た
 * - 音声付き (Muxed) ストリームも DASH 合成の映像候補に含めていた
 */
object StreamSelector {

    private val HEIGHT_REGEX = Regex("(\\d{3,4})")
    const val DEFAULT_HEIGHT = 720

    /**
     * "4K (2160p)" / "1080p60" / "720p" などの画質表記から縦解像度を取り出す
     * 数字を含まない "4K" 表記は 2160 とみなす。判定不能なら null
     */
    fun parseHeight(quality: String?): Int? {
        if (quality.isNullOrBlank()) return null
        HEIGHT_REGEX.find(quality)?.value?.toIntOrNull()?.let { return it }
        return if (quality.contains("4K", ignoreCase = true)) 2160 else null
    }

    fun isAvc(stream: VideoStream): Boolean {
        val codec = stream.codec
        if (codec.isNotBlank()) return codec.startsWith("avc", ignoreCase = true) || codec.startsWith("h264", ignoreCase = true)
        // コーデック不明 (旧データ等) の場合のみコンテナで推定
        return stream.format.equals("mp4", ignoreCase = true) && !stream.url.contains("av01", ignoreCase = true)
    }

    fun isAv1(stream: VideoStream): Boolean = stream.codec.startsWith("av01", ignoreCase = true)

    /** コーデック表記 ("avc1.4D401F" / "vp09.00.40.08" / "av01.0.08M.08" 等) から MIME タイプを求める。不明なら null */
    fun mimeTypeOf(stream: VideoStream): String? {
        val codec = stream.codec.lowercase()
        return when {
            codec.startsWith("avc") || codec.startsWith("h264") -> "video/avc"
            codec.startsWith("vp09") || codec.startsWith("vp9") -> "video/x-vnd.on2.vp9"
            codec.startsWith("av01") -> "video/av01"
            codec.startsWith("hev") || codec.startsWith("hvc") -> "video/hevc"
            else -> null
        }
    }

    /**
     * 端末がデコードできるストリームだけを残す
     * (4K / 1440p は VP9・AV1 でしか配信されないため、非対応端末で選ぶと再生に失敗する)
     * コーデックまたは解像度が不明なものは判定できないため残す。全て非対応と判定された場合は元のリストを返す
     */
    fun filterDecodable(
        streams: List<VideoStream>,
        canDecode: (mimeType: String, height: Int) -> Boolean
    ): List<VideoStream> =
        streams.filter { stream ->
            val mime = mimeTypeOf(stream) ?: return@filter true
            val height = parseHeight(stream.resolution) ?: return@filter true
            canDecode(mime, height)
        }.ifEmpty { streams }

    /**
     * 目的の画質に最も近いストリーム群を返す
     * 1. 同じ縦解像度があればそれ
     * 2. 無ければ目的以下で最大の解像度 (HD 端末で 4K を掴まない)
     * 3. それも無ければ目的より上で最小の解像度
     */
    fun poolForHeight(streams: List<VideoStream>, targetHeight: Int): List<VideoStream> {
        if (streams.isEmpty()) return emptyList()
        val byHeight = streams.groupBy { parseHeight(it.resolution) ?: -1 }
        byHeight[targetHeight]?.let { return it }
        val lower = byHeight.keys.filter { it in 1 until targetHeight }.maxOrNull()
        if (lower != null) return byHeight.getValue(lower)
        val higher = byHeight.keys.filter { it > targetHeight }.minOrNull()
        if (higher != null) return byHeight.getValue(higher)
        return streams
    }

    /**
     * @param targetQuality 設定または再生中メニューで選択された画質表記
     * @param preferAvc AVC (H.264) ハードウェア再生を優先するか
     * @param supportsAv1 端末に AV1 デコーダーがあるか (無い場合 AV1 ストリームは候補から除外)
     */
    fun selectVideo(
        streams: List<VideoStream>,
        targetQuality: String?,
        preferAvc: Boolean,
        supportsAv1: Boolean = true
    ): VideoStream? {
        if (streams.isEmpty()) return null
        val playable = streams.filter { supportsAv1 || !isAv1(it) }.ifEmpty { streams }
        val targetHeight = parseHeight(targetQuality) ?: DEFAULT_HEIGHT
        val pool = poolForHeight(playable, targetHeight)

        if (preferAvc) {
            pool.filter { isAvc(it) }.maxByOrNull { it.bitrate }?.let { return it }
        }
        // AVC が無い (4K / 1440p など) か AVC 優先が無効: 最高ビットレートを採用
        return pool.maxByOrNull { it.bitrate } ?: pool.firstOrNull()
    }

    /**
     * Fire TV ハードウェアデコーダー互換性と YouTube CDN 安定性が最も高い m4a (AAC) を最優先
     */
    fun selectAudio(streams: List<AudioStream>): AudioStream? {
        if (streams.isEmpty()) return null
        val m4a = streams.filter { it.format.equals("m4a", ignoreCase = true) }
        if (m4a.isNotEmpty()) return m4a.maxByOrNull { it.bitrate } ?: m4a.first()
        return streams.maxByOrNull { it.bitrate } ?: streams.firstOrNull()
    }
}
