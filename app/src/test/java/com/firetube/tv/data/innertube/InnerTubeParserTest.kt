package com.firetube.tv.data.innertube

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実際の InnerTube 応答 (識別子・ストリーム URL を除去済み) を用いた解析テスト
 * YouTube の応答構造が変わった際に、どの解析が壊れたかを即座に検出するためのもの
 */
class InnerTubeParserTest {

    private fun fixture(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("innertube/$name")
            ?: error("fixture not found: $name")
        return stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    @Test
    fun `通常検索は動画のみを返しショートは含まない`() {
        val items = InnerTubeClient.parseVideoRenderers(fixture("search_videos_and_shorts.json"))
        assertTrue("動画が1件以上解析されること", items.isNotEmpty())
        items.forEach { item ->
            assertTrue("動画IDの形式: ${item.id}", videoIdRegex.matches(item.id))
            assertTrue("タイトルが空でないこと", item.title.isNotBlank())
            assertFalse("ショートは含まない", item.uploaderName == "ショート")
        }
        assertEquals("重複IDが無いこと", items.size, items.map { it.id }.toSet().size)
    }

    @Test
    fun `ショート指定時はショート動画も解析される`() {
        val json = fixture("search_videos_and_shorts.json")
        val normal = InnerTubeClient.parseVideoRenderers(json)
        val withShorts = InnerTubeClient.parseVideoRenderers(json, includeShorts = true)
        val shorts = withShorts.filter { it.uploaderName == "ショート" }
        assertTrue("ショートが解析されること", shorts.isNotEmpty())
        shorts.forEach { assertTrue(videoIdRegex.matches(it.id)) }
        assertTrue(withShorts.size > normal.size)
    }

    @Test
    fun `再生リストは指定時のみ専用IDで解析される`() {
        val json = fixture("search_playlists.json")
        val playlists = InnerTubeClient.parseVideoRenderers(json, includePlaylists = true)
            .filter { it.id.startsWith(InnerTubeClient.PLAYLIST_ID_PREFIX) }
        assertTrue("再生リストが解析されること", playlists.isNotEmpty())
        playlists.forEach { pl ->
            assertTrue(pl.id.removePrefix(InnerTubeClient.PLAYLIST_ID_PREFIX).isNotBlank())
            assertTrue(pl.title.isNotBlank())
        }

        val withoutFlag = InnerTubeClient.parseVideoRenderers(json)
        assertTrue(withoutFlag.none { it.id.startsWith(InnerTubeClient.PLAYLIST_ID_PREFIX) })
    }
}
