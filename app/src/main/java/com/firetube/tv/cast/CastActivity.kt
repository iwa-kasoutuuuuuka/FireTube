package com.firetube.tv.cast

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.firetube.tv.R

/**
 * スマホからのキャスト案内画面
 * 同一Wi-Fi内のスマホからアクセスするためのURLを表示
 */
class CastActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast)

        // キャストサーバーを確実に稼働
        LocalCastServer.start(this)

        val textUrl = findViewById<TextView>(R.id.text_cast_url)
        val btnClose = findViewById<Button>(R.id.btn_close_cast)

        val ip = LocalCastServer.getLocalIpAddress()
        textUrl.text = "http://$ip:${LocalCastServer.PORT}"

        btnClose.setOnClickListener {
            finish()
        }
        btnClose.requestFocus()
    }
}
