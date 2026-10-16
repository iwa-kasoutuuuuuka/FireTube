package com.firetube.tv.data.network

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Request

/**
 * リモート設定 (リポジトリ直下の remote-config.json)
 *
 * Piped インスタンスの停止や YouTube 側のクライアント制限など「上流の変化」に、
 * APK を再配布せず設定ファイルの更新だけで追従するためのもの
 * - 取得失敗・不正な内容の場合は、前回取得分 → アプリ内蔵の既定値 の順で使用する
 * - 項目単位で検証し、不正な項目だけを既定値に戻す
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"
    const val CONFIG_URL = "https://raw.githubusercontent.com/iwa-kasoutuuuuuka/FireTube/main/remote-config.json"

    private const val PREFS_NAME = "remote_config"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_LIST_SIZE = 8

    // InnerTubeClient が実装している player クライアント名
    const val CLIENT_IOS_KIDS = "IOS_KIDS"
    const val CLIENT_VISIONOS = "VISIONOS"
    const val CLIENT_IOS_EMBEDDED = "IOS_EMBEDDED"
    const val CLIENT_MWEB = "MWEB"
    const val CLIENT_ANDROID_KIDS = "ANDROID_KIDS"
    const val CLIENT_IOS = "IOS"

    val KNOWN_PLAYER_CLIENTS = setOf(
        CLIENT_IOS_KIDS, CLIENT_VISIONOS, CLIENT_IOS_EMBEDDED, CLIENT_MWEB, CLIENT_ANDROID_KIDS, CLIENT_IOS
    )

    /** 検索・browse 用 WEB クライアントの clientVersions キー */
    const val VERSION_KEY_WEB = "WEB"

    // clientVersions で上書きできるクライアント (IOS_EMBEDDED は IOS と同じコンテキストを使う)
    private val VERSION_KEYS = setOf(
        VERSION_KEY_WEB, CLIENT_IOS, CLIENT_IOS_KIDS, CLIENT_ANDROID_KIDS, CLIENT_MWEB, CLIENT_VISIONOS
    )
    private val VERSION_REGEX = Regex("^[0-9][0-9.]{1,23}$")

    data class Config(
        /** Piped API のベース URL (先頭から順に試行) */
        val pipedInstances: List<String>,
        /** 並列に発火し、先頭側を優先して採用する player クライアント */
        val playerRaceClients: List<String>,
        /** Racing が全滅した場合に順に試す player クライアント */
        val playerFallbackClients: List<String>,
        /** 「人気の動画」行の検索フォールバックで使うクエリ */
        val popularQueries: List<String>,
        /** クライアント名 → clientVersion の上書き (未指定のクライアントはアプリ内蔵の値) */
        val clientVersions: Map<String, String> = emptyMap()
    )

    val DEFAULT = Config(
        // 2026-10 実測: private.coffee は稼働、kavin.rocks は接続タイムアウトが多いため末尾
        pipedInstances = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi-libre.kavin.rocks"
        ),
        playerRaceClients = listOf(CLIENT_IOS_KIDS, CLIENT_VISIONOS),
        playerFallbackClients = listOf(CLIENT_IOS_EMBEDDED, CLIENT_MWEB, CLIENT_ANDROID_KIDS, CLIENT_IOS),
        popularQueries = listOf("ニュース", "音楽 MV", "ゲーム実況", "スポーツ ハイライト", "アニメ 公式")
    )

    @Volatile
    var current: Config = DEFAULT
        private set

    /**
     * 設定 JSON を解析する (純粋関数・ユニットテスト対象)
     * @return JSON として解釈できない場合は null。項目が欠落・不正な場合はその項目のみ既定値
     */
    fun parse(json: String): Config? {
        val root = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        val race = root.stringList("playerRaceClients").filter { it in KNOWN_PLAYER_CLIENTS }.distinct()
        val fallback = root.stringList("playerFallbackClients")
            .filter { it in KNOWN_PLAYER_CLIENTS && it !in race }.distinct()
        // 未知のクライアント名しか無い (新しい設定を古いアプリが読んだ) 場合は両方とも既定値に戻す
        val clientsValid = race.isNotEmpty() || fallback.isNotEmpty()

        return Config(
            pipedInstances = root.stringList("pipedInstances")
                .map { it.trimEnd('/') }
                .filter { isSafeBaseUrl(it) }
                .distinct()
                .ifEmpty { DEFAULT.pipedInstances },
            playerRaceClients = if (clientsValid) race else DEFAULT.playerRaceClients,
            playerFallbackClients = if (clientsValid) fallback else DEFAULT.playerFallbackClients,
            popularQueries = root.stringList("popularQueries")
                .filter { it.length <= 40 }
                .distinct()
                .ifEmpty { DEFAULT.popularQueries },
            clientVersions = root.versionMap("clientVersions")
        )
    }

    /** 既知のクライアント名かつ "数字とドットのみ" の値だけを採用 (ヘッダー・JSON への不正な文字の混入防止) */
    private fun JsonObject.versionMap(key: String): Map<String, String> {
        val obj = get(key) as? JsonObject ?: return emptyMap()
        return obj.entrySet().mapNotNull { (name, value) ->
            val version = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
            if (name in VERSION_KEYS && version != null && VERSION_REGEX.matches(version)) name to version else null
        }.toMap()
    }

    /** https のホスト名のみ許可 (平文 HTTP・認証情報付き・クエリ付き URL は拒否) */
    private fun isSafeBaseUrl(url: String): Boolean =
        Regex("^https://[A-Za-z0-9.-]+(:\\d{1,5})?(/[A-Za-z0-9._/-]*)?$").matches(url)

    private fun JsonObject.stringList(key: String): List<String> {
        val array = get(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { elem ->
            if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) elem.asString.trim().takeIf { it.isNotEmpty() } else null
        }.take(MAX_LIST_SIZE)
    }

    /** 前回取得した設定を読み込む (通信なし) */
    fun loadCached(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_JSON, null) ?: return
        parse(saved)?.let { current = it }
    }

    /**
     * 設定を再取得する (ブロッキング。バックグラウンドスレッドから呼ぶこと)
     * 前回取得から一定時間内であれば通信しない
     */
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_FETCHED_AT, 0L) in 0 until REFRESH_INTERVAL_MS) return
        try {
            val request = Request.Builder().url(CONFIG_URL).get().build()
            NetworkClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Remote config HTTP ${response.code}, keeping current config")
                    return
                }
                val body = response.body?.string() ?: return
                val config = parse(body)
                if (config == null) {
                    Log.w(TAG, "Remote config is not valid JSON, keeping current config")
                    return
                }
                current = config
                prefs.edit().putString(KEY_JSON, body).putLong(KEY_FETCHED_AT, now).apply()
                Log.i(TAG, "Remote config applied: piped=${config.pipedInstances.size}, race=${config.playerRaceClients}, fallback=${config.playerFallbackClients}, versions=${config.clientVersions.keys}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote config fetch failed, keeping current config: ${e.message}")
        }
    }
}
