package com.firetube.tv.data.model

/**
 * チャンネル識別キーの正規化
 * 各プロバイダが返すチャンネル URL の表記揺れを吸収し、登録・取得に使う安定したキーへ変換する
 * - "https://www.youtube.com/channel/UCxxxx" / "UCxxxx" → "UCxxxx"
 * - "/@handle" / "https://www.youtube.com/@handle" → "@handle"
 * 旧バージョンではチャンネル名そのものをキーとして保存していたため、
 * それ以外の文字列は「レガシー (名前) キー」として扱う
 */
object ChannelKey {

    private val CHANNEL_ID_REGEX = Regex("(UC[A-Za-z0-9_-]{22})")
    private val HANDLE_REGEX = Regex("@([^/?#\\s]+)")

    fun fromUploaderUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        CHANNEL_ID_REGEX.find(url)?.let { return it.groupValues[1] }
        HANDLE_REGEX.find(url)?.let { return "@" + it.groupValues[1] }
        return null
    }

    fun isChannelId(key: String): Boolean = CHANNEL_ID_REGEX.matches(key)

    /** InnerTube で直接チャンネルを解決できるキーか (false はチャンネル名のレガシーキー) */
    fun isResolvable(key: String): Boolean = isChannelId(key) || key.startsWith("@")
}
