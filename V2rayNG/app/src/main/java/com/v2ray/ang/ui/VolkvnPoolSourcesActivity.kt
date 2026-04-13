package com.v2ray.ang.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityVolkvnPoolSourcesBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.VolkvnVpnBootstrap
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.launch

/**
 * Edit extra raw subscription URLs merged with the built-in VolKVN pool ([AppConfig.VOLKVN_SUBSCRIPTION_URLS]).
 */
class VolkvnPoolSourcesActivity : BaseActivity() {

    private lateinit var binding: ActivityVolkvnPoolSourcesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVolkvnPoolSourcesBinding.inflate(layoutInflater)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.volkvn_pool_sources_title))
        binding.etUrls.setText(MmkvManager.decodeSettingsString(AppConfig.PREF_VOLKVN_USER_POOL_URLS, "").orEmpty())
        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                showLoading()
                try {
                    val text = binding.etUrls.text?.toString().orEmpty()
                    MmkvManager.encodeSettings(AppConfig.PREF_VOLKVN_USER_POOL_URLS, text)
                    VolkvnVpnBootstrap.ensurePublicPoolSubscription(this@VolkvnPoolSourcesActivity)
                    VolkvnVpnBootstrap.refreshServersAndSelectBest(this@VolkvnPoolSourcesActivity)
                } finally {
                    hideLoading()
                }
                toast(R.string.volkvn_pool_sources_saved)
                finish()
            }
        }
    }
}
