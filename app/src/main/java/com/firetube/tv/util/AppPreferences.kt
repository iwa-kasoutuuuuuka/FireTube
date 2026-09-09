package com.firetube.tv.util

import android.content.Context
import android.content.SharedPreferences

/**
 * ユーザー設定管理（Leanback Preferences）
 * 画質固定、デフォルト速度、SponsorBlock、API優先順位を管理
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("firetube_preferences", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }

        const val KEY_DEFAULT_QUALITY = "pref_default_quality"
        const val KEY_DEFAULT_SPEED = "pref_default_speed"
        const val KEY_SB_SPONSOR = "pref_sb_sponsor"
        const val KEY_SB_INTRO = "pref_sb_intro"
        const val KEY_SB_BADGE = "pref_sb_badge"
        const val KEY_API_SOURCE = "pref_api_source"

        const val API_SOURCE_INNERTUBE = "InnerTube (推奨)"
        const val API_SOURCE_NEWPIPE = "NewPipe"
        const val API_SOURCE_PIPED = "Piped"
    }

    var defaultQuality: String
        get() = prefs.getString(KEY_DEFAULT_QUALITY, "720p") ?: "720p"
        set(value) = prefs.edit().putString(KEY_DEFAULT_QUALITY, value).apply()

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_SPEED, value).apply()

    var skipSponsor: Boolean
        get() = prefs.getBoolean(KEY_SB_SPONSOR, true)
        set(value) = prefs.edit().putBoolean(KEY_SB_SPONSOR, value).apply()

    var skipIntro: Boolean
        get() = prefs.getBoolean(KEY_SB_INTRO, true)
        set(value) = prefs.edit().putBoolean(KEY_SB_INTRO, value).apply()

    var showSponsorBadge: Boolean
        get() = prefs.getBoolean(KEY_SB_BADGE, true)
        set(value) = prefs.edit().putBoolean(KEY_SB_BADGE, value).apply()

    var apiSource: String
        get() = prefs.getString(KEY_API_SOURCE, API_SOURCE_INNERTUBE) ?: API_SOURCE_INNERTUBE
        set(value) = prefs.edit().putString(KEY_API_SOURCE, value).apply()
}
