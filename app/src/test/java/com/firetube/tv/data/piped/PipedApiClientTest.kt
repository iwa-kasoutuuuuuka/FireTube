package com.firetube.tv.data.piped

import org.junit.Assert.assertEquals
import org.junit.Test

class PipedApiClientTest {

    private val a = "https://a.example.org"
    private val b = "https://b.example.org"
    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun `失敗記録が無ければ設定順のまま返す`() {
        assertEquals(listOf(a, b), PipedApiClient.availableInstances(listOf(a, b), emptyMap(), now))
    }

    @Test
    fun `直近に失敗したインスタンスはスキップし、時間が経てば再び試す`() {
        assertEquals(listOf(b), PipedApiClient.availableInstances(listOf(a, b), mapOf(a to now - minute), now))
        assertEquals(listOf(a, b), PipedApiClient.availableInstances(listOf(a, b), mapOf(a to now - 6 * minute), now))
    }

    @Test
    fun `全インスタンスが失敗中なら全て試す`() {
        val failed = mapOf(a to now - minute, b to now - 2 * minute)
        assertEquals(listOf(a, b), PipedApiClient.availableInstances(listOf(a, b), failed, now))
    }

    @Test
    fun `端末の時計が巻き戻った場合はスキップしない`() {
        assertEquals(listOf(a, b), PipedApiClient.availableInstances(listOf(a, b), mapOf(a to now + minute), now))
    }
}
