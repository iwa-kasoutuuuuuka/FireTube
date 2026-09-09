package com.firetube.tv.ui.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * 設定画面 Activity
 */
class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            val fragment = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_fragment_container, fragment)
                .commit()
        }
    }
}
