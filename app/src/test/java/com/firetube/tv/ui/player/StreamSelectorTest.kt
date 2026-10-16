package com.firetube.tv.ui.player

import com.firetube.tv.data.model.AudioStream
import com.firetube.tv.data.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSelectorTest {

    private fun v(res: String, codec: String, bitrate: Int, format: String = if (codec.startsWith("vp")) "webm" else "mp4", videoOnly: Boolean = true) =
        VideoStream(url = "https://example/$res/$codec", resolution = res, format = format, isVideoOnly = videoOnly, bitrate = bitrate, codec = codec)

    // 実際の InnerTube (IOS) 応答と同じ並び: 各解像度に vp9 / av01 / avc1 が混在
    private val iosLike = listOf(
        v("2160p", "vp09.00.50.08", 18_000_000),
        v("2160p", "av01.0.12M.08", 17_400_000),
        v("1080p", "avc1.640028", 4_300_000),
        v("1080p", "vp09.00.40.08", 1_500_000),
        v("1080p", "av01.0.08M.08", 1_600_000),
        v("720p", "av01.0.05M.08", 1_012_000),   // AV1 を AVC より前に並べる
        v("720p", "avc1.4D401F", 1_058_000),
        v("720p", "vp09.00.31.08", 867_000),
        v("360p", "avc1.4D401E", 450_000),
        v("360p", "av01.0.01M.08", 304_000)
    )

    @Test
    fun `画質表記から縦解像度を取り出す`() {
        assertEquals(2160, StreamSelector.parseHeight("4K (2160p)"))
        assertEquals(2160, StreamSelector.parseHeight("4K"))
        assertEquals(1080, StreamSelector.parseHeight("1080p60"))
        assertEquals(720, StreamSelector.parseHeight("720p"))
        assertEquals(360, StreamSelector.parseHeight("360p"))
        assertNull(StreamSelector.parseHeight(null))
        assertNull(StreamSelector.parseHeight("auto"))
    }

    @Test
    fun `360p を選ぶと 720p ではなく 360p が選ばれる (旧実装の退行防止)`() {
        val picked = StreamSelector.selectVideo(iosLike, "360p", preferAvc = true)
        assertEquals("360p", picked?.resolution)
        assertEquals("avc1.4D401E", picked?.codec)
    }

    @Test
    fun `AVC 優先時は同じ mp4 コンテナでも AV1 ではなく avc1 を選ぶ`() {
        val picked = StreamSelector.selectVideo(iosLike, "720p", preferAvc = true)
        assertEquals("avc1.4D401F", picked?.codec)
    }

    @Test
    fun `AVC が存在しない 4K は最高ビットレートを選ぶ`() {
        val picked = StreamSelector.selectVideo(iosLike, "4K (2160p)", preferAvc = true)
        assertEquals("2160p", picked?.resolution)
        assertEquals("vp09.00.50.08", picked?.codec)
    }

    @Test
    fun `AV1 非対応端末では AV1 ストリームを候補から除外する`() {
        val picked = StreamSelector.selectVideo(iosLike, "4K (2160p)", preferAvc = false, supportsAv1 = false)
        assertEquals("vp09.00.50.08", picked?.codec)
        val only720Av1 = listOf(v("720p", "av01.0.05M.08", 1_000_000), v("480p", "avc1.4D401E", 600_000))
        assertEquals("480p", StreamSelector.selectVideo(only720Av1, "720p", preferAvc = false, supportsAv1 = false)?.resolution)
    }

    @Test
    fun `目的の画質が無い場合は下位で最大、無ければ上位で最小を選ぶ (HD 端末で 4K を掴まない)`() {
        val streams = listOf(v("2160p", "vp09", 18_000_000), v("480p", "avc1", 600_000), v("360p", "avc1", 400_000))
        assertEquals("480p", StreamSelector.selectVideo(streams, "1080p", preferAvc = true)?.resolution)
        val onlyHigher = listOf(v("2160p", "vp09", 18_000_000), v("1440p", "vp09", 9_000_000))
        assertEquals("1440p", StreamSelector.selectVideo(onlyHigher, "720p", preferAvc = true)?.resolution)
    }

    @Test
    fun `コーデック不明の旧データは mp4 コンテナを AVC とみなす`() {
        val legacy = listOf(v("720p", "", 800_000, format = "webm"), v("720p", "", 900_000, format = "mp4"))
        assertEquals("mp4", StreamSelector.selectVideo(legacy, "720p", preferAvc = true)?.format)
    }

    @Test
    fun `音声は m4a を最優先し、その中で最高ビットレートを選ぶ`() {
        val streams = listOf(
            AudioStream(url = "a", format = "webm", bitrate = 160_000),
            AudioStream(url = "b", format = "m4a", bitrate = 48_000),
            AudioStream(url = "c", format = "m4a", bitrate = 128_000)
        )
        assertEquals("c", StreamSelector.selectAudio(streams)?.url)
        assertNull(StreamSelector.selectAudio(emptyList()))
    }

    @Test
    fun `コーデック表記から MIME タイプを求める`() {
        assertEquals("video/avc", StreamSelector.mimeTypeOf(v("720p", "avc1.4D401F", 1)))
        assertEquals("video/x-vnd.on2.vp9", StreamSelector.mimeTypeOf(v("720p", "vp09.00.31.08", 1)))
        assertEquals("video/x-vnd.on2.vp9", StreamSelector.mimeTypeOf(v("720p", "vp9", 1)))
        assertEquals("video/av01", StreamSelector.mimeTypeOf(v("720p", "av01.0.05M.08", 1)))
        assertNull(StreamSelector.mimeTypeOf(v("720p", "", 1)))
    }

    @Test
    fun `端末がデコードできない解像度・コーデックは候補から除外される`() {
        // 1080p までの AVC / VP9 のみ対応する HD 端末を想定
        val hdDevice = { mime: String, height: Int -> mime != "video/av01" && height <= 1080 }
        val decodable = StreamSelector.filterDecodable(iosLike, hdDevice)
        assertEquals(setOf("1080p", "720p", "360p"), decodable.map { it.resolution }.toSet())
        assertEquals(0, decodable.count { StreamSelector.isAv1(it) })
        // 4K を指定しても、デコード可能な最大の 1080p に落ちる
        assertEquals("1080p", StreamSelector.selectVideo(decodable, "2160p", preferAvc = true)?.resolution)
    }

    @Test
    fun `コーデック不明は残し、全て非対応と判定された場合は元のリストを返す`() {
        val unknown = listOf(v("2160p", "", 1), v("720p", "avc1.4D401F", 1))
        assertEquals(unknown, StreamSelector.filterDecodable(unknown) { _, height -> height <= 1080 })
        assertEquals(iosLike, StreamSelector.filterDecodable(iosLike) { _, _ -> false })
    }
}
