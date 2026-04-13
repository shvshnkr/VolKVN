package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.AppConfig
import com.v2ray.ang.databinding.ActivityConsentBinding
import com.v2ray.ang.handler.BabukVpnBootstrap
import com.v2ray.ang.handler.MmkvManager

/** First launch: accept third-party pool risks, then Simple UI. */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_BABUK_CONSENT_ACCEPTED, false)) {
            goMain()
            return
        }
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccept.setOnClickListener {
            MmkvManager.encodeSettings(AppConfig.PREF_BABUK_CONSENT_ACCEPTED, true)
            BabukVpnBootstrap.schedulePublicPoolWorker()
            goMain()
        }
        binding.btnDecline.setOnClickListener {
            finishAffinity()
        }
    }

    private fun goMain() {
        startActivity(Intent(this, SimpleMainActivity::class.java))
        finish()
    }
}
