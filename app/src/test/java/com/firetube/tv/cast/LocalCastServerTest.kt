package com.firetube.tv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCastServerTest {

    @Test
    fun `各種YouTube URL形式から動画IDを抽出する`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "https://www.youtube.com/watch?feature=share&v=$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube.com/embed/$id",
            "https://m.youtube.com/watch?v=$id&t=30s"
        ).forEach { url ->
            assertEquals(url, id, LocalCastServer.extractVideoId(url))
        }
    }

    @Test
    fun `ID文字種以外を含む入力は拒否する (JS HTML インジェクション対策)`() {
        listOf(
            "'+alert()+'",          // 11 文字の JS 注入
            "<script>1</",          // 11 文字の HTML 注入
            "https://www.youtube.com/watch?v='+alert()+'",
            "abc",
            "",
            "https://example.com/"
        ).forEach { input ->
            assertNull(input, LocalCastServer.extractVideoId(input))
        }
    }
}
