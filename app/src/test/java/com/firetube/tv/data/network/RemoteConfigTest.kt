package com.firetube.tv.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RemoteConfigTest {

    @Test
    fun `JSON として解釈できない内容は null を返す`() {
        assertNull(RemoteConfig.parse(""))
        assertNull(RemoteConfig.parse("<html>404</html>"))
        assertNull(RemoteConfig.parse("[1, 2]"))
    }

    @Test
    fun `項目が欠落している場合は既定値を使う`() {
        assertEquals(RemoteConfig.DEFAULT, RemoteConfig.parse("{}"))
    }

    @Test
    fun `有効な設定はそのまま反映される`() {
        val config = RemoteConfig.parse(
            """
            {
              "pipedInstances": ["https://piped.example.org/", "https://api.example.net:8443/piped"],
              "playerRaceClients": ["VISIONOS"],
              "playerFallbackClients": ["IOS", "MWEB"],
              "popularQueries": ["映画 予告"]
            }
            """
        )!!
        assertEquals(listOf("https://piped.example.org", "https://api.example.net:8443/piped"), config.pipedInstances)
        assertEquals(listOf("VISIONOS"), config.playerRaceClients)
        assertEquals(listOf("IOS", "MWEB"), config.playerFallbackClients)
        assertEquals(listOf("映画 予告"), config.popularQueries)
    }

    @Test
    fun `https 以外や不正な形式のインスタンス URL は除外する`() {
        val config = RemoteConfig.parse(
            """
            {"pipedInstances": [
              "http://insecure.example.org",
              "https://user:pass@evil.example.org",
              "https://example.org/?redirect=1",
              "javascript:alert(1)",
              42,
              "https://ok.example.org"
            ]}
            """
        )!!
        assertEquals(listOf("https://ok.example.org"), config.pipedInstances)
    }

    @Test
    fun `有効なインスタンスが 1 件も無ければ既定値に戻す`() {
        val config = RemoteConfig.parse("""{"pipedInstances": ["http://insecure.example.org"]}""")!!
        assertEquals(RemoteConfig.DEFAULT.pipedInstances, config.pipedInstances)
    }

    @Test
    fun `未知のクライアント名は無視し Racing と重複するフォールバックは除く`() {
        val config = RemoteConfig.parse(
            """
            {
              "playerRaceClients": ["FUTURE_CLIENT", "IOS_KIDS", "IOS_KIDS"],
              "playerFallbackClients": ["IOS_KIDS", "IOS", "ANOTHER_FUTURE"]
            }
            """
        )!!
        assertEquals(listOf("IOS_KIDS"), config.playerRaceClients)
        assertEquals(listOf("IOS"), config.playerFallbackClients)
    }

    @Test
    fun `Racing を空にしてフォールバックのみにできる`() {
        val config = RemoteConfig.parse("""{"playerRaceClients": [], "playerFallbackClients": ["MWEB", "IOS"]}""")!!
        assertEquals(emptyList<String>(), config.playerRaceClients)
        assertEquals(listOf("MWEB", "IOS"), config.playerFallbackClients)
    }

    @Test
    fun `未知のクライアント名しか無い場合は既定の順序に戻す`() {
        val config = RemoteConfig.parse("""{"playerRaceClients": ["FUTURE_A"], "playerFallbackClients": ["FUTURE_B"]}""")!!
        assertEquals(RemoteConfig.DEFAULT.playerRaceClients, config.playerRaceClients)
        assertEquals(RemoteConfig.DEFAULT.playerFallbackClients, config.playerFallbackClients)
    }

    @Test
    fun `clientVersions は既知のクライアントかつ数字とドットのみの値だけ採用する`() {
        val config = RemoteConfig.parse(
            """
            {"clientVersions": {
              "IOS": "21.40.3",
              "WEB": " 2.20261001.00.00 ",
              "IOS_KIDS": "9.50.0\r\nX-Injected: 1",
              "MWEB": "abc",
              "VISIONOS": 2,
              "FUTURE_CLIENT": "1.0"
            }}
            """
        )!!
        assertEquals(mapOf("IOS" to "21.40.3", "WEB" to "2.20261001.00.00"), config.clientVersions)
        assertEquals(emptyMap<String, String>(), RemoteConfig.parse("""{"clientVersions": []}""")!!.clientVersions)
    }

    @Test
    fun `リポジトリ直下の remote-config json は内蔵の既定値と一致する`() {
        // ユニットテストの作業ディレクトリは app/ モジュール
        val file = listOf(File("../remote-config.json"), File("remote-config.json")).firstOrNull { it.exists() }
        assertNotNull("remote-config.json が見つからない", file)
        assertEquals(RemoteConfig.DEFAULT, RemoteConfig.parse(file!!.readText(Charsets.UTF_8)))
    }
}
