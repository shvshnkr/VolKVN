package com.v2ray.ang.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBabukPoolSourcesBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.BabukVpnBootstrap
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.launch

/**
 * Edit extra raw subscription URLs merged with the built-in VolKVN pool ([AppConfig.BABUK_SUBSCRIPTION_URLS]).
 */
class BabukPoolSourcesActivity : BaseActivity() {

    private lateinit var binding: ActivityBabukPoolSourcesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBabukPoolSourcesBinding.inflate(layoutInflater)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.babuk_pool_sources_title))
        binding.etUrls.setText(MmkvManager.decodeSettingsString(AppConfig.PREF_BABUK_USER_POOL_URLS, "").orEmpty())
        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                showLoading()
                try {
                    val text = binding.etUrls.text?.toString().orEmpty()
                    MmkvManager.encodeSettings(AppConfig.PREF_BABUK_USER_POOL_URLS, text)
                    BabukVpnBootstrap.ensurePublicPoolSubscription(this@BabukPoolSourcesActivity)
                    BabukVpnBootstrap.refreshServersAndSelectBest(this@BabukPoolSourcesActivity)
                } finally {
                    hideLoading()
                }
                toast(R.string.babuk_pool_sources_saved)
                finish()
            }
        }
    }
}
