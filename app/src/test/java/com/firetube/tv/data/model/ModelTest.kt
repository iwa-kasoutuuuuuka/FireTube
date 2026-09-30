package com.firetube.tv.data.model

import com.firetube.tv.data.local.WatchProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun `チャンネルURLからIDとハンドルを正規化する`() {
        val uc = "UCNn8jS0t8XZJuVlPZnBGo7g"
        assertEquals(uc, ChannelKey.fromUploaderUrl(uc))
        assertEquals(uc, ChannelKey.fromUploaderUrl("https://www.youtube.com/channel/$uc"))
        assertEquals("@shimajiro", ChannelKey.fromUploaderUrl("/@shimajiro"))
        assertEquals("@shimajiro", ChannelKey.fromUploaderUrl("https://www.youtube.com/@shimajiro/videos"))
        assertNull(ChannelKey.fromUploaderUrl(null))
        assertNull(ChannelKey.fromUploaderUrl("しまじろうチャンネル（公式）"))
    }

    @Test
    fun `旧バージョンのチャンネル名キーは解決不可として扱う`() {
        assertTrue(ChannelKey.isResolvable("UCNn8jS0t8XZJuVlPZnBGo7g"))
        assertTrue(ChannelKey.isResolvable("@shimajiro"))
        assertFalse(ChannelKey.isResolvable("しまじろうチャンネル（公式）"))
    }

    @Test
    fun `非公開・削除動画を除外し特殊カードは許可する`() {
        fun item(id: String, title: String) = VideoItem(id = id, title = title, uploaderName = "c", thumbnailUrl = "")
        assertTrue(item("dQw4w9WgXcQ", "普通の動画").isPlayableAndValid)
        assertFalse(item("dQw4w9WgXcQ", "非公開動画").isPlayableAndValid)
        assertFalse(item("dQw4w9WgXcQ", "[Deleted video]").isPlayableAndValid)
        assertFalse(item("dQw4w9WgXcQ", "  ").isPlayableAndValid)
        assertFalse(item("", "タイトル").isPlayableAndValid)
        assertTrue(item("__settings__", "設定").isPlayableAndValid)
    }

    @Test
    fun `再生時間の表示形式`() {
        fun dur(sec: Long) = VideoItem(id = "x", title = "t", uploaderName = "c", thumbnailUrl = "", durationSeconds = sec).formattedDuration
        assertEquals("", dur(0))
        assertEquals("01:05", dur(65))
        assertEquals("1:01:01", dur(3661))
    }

    @Test
    fun `視聴済み位置バーの割合`() {
        WatchProgressStore.clear()
        WatchProgressStore.update("a", positionMs = 60_000, durationMs = 100_000)
        assertEquals(0.6f, WatchProgressStore.get("a")!!, 0.001f)

        // 冒頭 5 秒未満は未視聴扱い
        WatchProgressStore.update("b", positionMs = 3_000, durationMs = 100_000)
        assertNull(WatchProgressStore.get("b"))

        // 終盤は視聴完了として満タン
        WatchProgressStore.update("c", positionMs = 97_000, durationMs = 100_000)
        assertEquals(1f, WatchProgressStore.get("c")!!, 0.001f)

        val before = WatchProgressStore.version
        WatchProgressStore.remove("a")
        assertNull(WatchProgressStore.get("a"))
        assertTrue(WatchProgressStore.version > before)
    }
}
