package com.firetube.tv.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.firetube.tv.R
import com.firetube.tv.util.AppPreferences

/**
 * Fire TV 向け設定画面 (Leanback GuidedStepSupportFragment)
 * 物理リモコン操作で画質、再生速度、SponsorBlock、APIデータソースを快適に設定
 */
class SettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val ACTION_QUALITY = 1L
        private const val ACTION_SPEED = 2L
        private const val ACTION_SB_SPONSOR = 3L
        private const val ACTION_SB_INTRO = 4L
        private const val ACTION_SB_BADGE = 5L
        private const val ACTION_API_SOURCE = 6L

        private val QUALITY_OPTIONS = listOf("720p", "1080p", "480p")
        private val SPEED_OPTIONS = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        private val API_OPTIONS = listOf(
            AppPreferences.API_SOURCE_INNERTUBE,
            AppPreferences.API_SOURCE_NEWPIPE,
            AppPreferences.API_SOURCE_PIPED
        )
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            "画質、倍速、SponsorBlock自動スキップ、APIデータソースの設定を変更できます。",
            getString(R.string.app_name),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val pref = AppPreferences.getInstance(requireContext())

        // 1. デフォルト画質
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_QUALITY)
                .title(getString(R.string.pref_default_quality))
                .description("${pref.defaultQuality} (決定で切替)")
                .build()
        )

        // 2. デフォルト速度
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SPEED)
                .title(getString(R.string.pref_default_speed))
                .description("${pref.defaultSpeed}x (決定で切替)")
                .build()
        )

        // 3. SponsorBlock: 案件スキップ
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SB_SPONSOR)
                .title(getString(R.string.pref_sb_sponsor))
                .description(if (pref.skipSponsor) "有効 (ON)" else "無効 (OFF)")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(pref.skipSponsor)
                .build()
        )

        // 4. SponsorBlock: イントロスキップ
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SB_INTRO)
                .title(getString(R.string.pref_sb_intro))
                .description(if (pref.skipIntro) "有効 (ON)" else "無効 (OFF)")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(pref.skipIntro)
                .build()
        )

        // 5. SponsorBlock: スキップバッジ通知
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SB_BADGE)
                .title(getString(R.string.pref_sb_badge))
                .description(if (pref.showSponsorBadge) "表示 (ON)" else "非表示 (OFF)")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(pref.showSponsorBadge)
                .build()
        )

        // 6. 優先APIソース
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_API_SOURCE)
                .title(getString(R.string.pref_api_source))
                .description("${pref.apiSource} (決定で切替)")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val pref = AppPreferences.getInstance(requireContext())

        when (action.id) {
            ACTION_QUALITY -> {
                val currentIndex = QUALITY_OPTIONS.indexOf(pref.defaultQuality)
                val nextIndex = (currentIndex + 1) % QUALITY_OPTIONS.size
                val newQuality = QUALITY_OPTIONS[nextIndex]
                pref.defaultQuality = newQuality
                action.description = "$newQuality (決定で切替)"
                notifyActionChanged(findActionPositionById(ACTION_QUALITY))
                Toast.makeText(requireContext(), "画質を $newQuality に設定しました", Toast.LENGTH_SHORT).show()
            }

            ACTION_SPEED -> {
                val currentIndex = SPEED_OPTIONS.indexOf(pref.defaultSpeed)
                val nextIndex = (currentIndex + 1) % SPEED_OPTIONS.size
                val newSpeed = SPEED_OPTIONS[nextIndex]
                pref.defaultSpeed = newSpeed
                action.description = "${newSpeed}x (決定で切替)"
                notifyActionChanged(findActionPositionById(ACTION_SPEED))
                Toast.makeText(requireContext(), "再生速度を ${newSpeed}x に設定しました", Toast.LENGTH_SHORT).show()
            }

            ACTION_SB_SPONSOR -> {
                val newValue = !pref.skipSponsor
                pref.skipSponsor = newValue
                action.isChecked = newValue
                action.description = if (newValue) "有効 (ON)" else "無効 (OFF)"
                notifyActionChanged(findActionPositionById(ACTION_SB_SPONSOR))
            }

            ACTION_SB_INTRO -> {
                val newValue = !pref.skipIntro
                pref.skipIntro = newValue
                action.isChecked = newValue
                action.description = if (newValue) "有効 (ON)" else "無効 (OFF)"
                notifyActionChanged(findActionPositionById(ACTION_SB_INTRO))
            }

            ACTION_SB_BADGE -> {
                val newValue = !pref.showSponsorBadge
                pref.showSponsorBadge = newValue
                action.isChecked = newValue
                action.description = if (newValue) "表示 (ON)" else "非表示 (OFF)"
                notifyActionChanged(findActionPositionById(ACTION_SB_BADGE))
            }

            ACTION_API_SOURCE -> {
                val currentIndex = API_OPTIONS.indexOf(pref.apiSource)
                val nextIndex = (currentIndex + 1) % API_OPTIONS.size
                val newApi = API_OPTIONS[nextIndex]
                pref.apiSource = newApi
                action.description = "$newApi (決定で切替)"
                notifyActionChanged(findActionPositionById(ACTION_API_SOURCE))
                Toast.makeText(requireContext(), "優先データソースを $newApi に設定しました", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
