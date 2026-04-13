package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySimpleMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.BabukDebugLog
import com.v2ray.ang.handler.BabukVpnBootstrap
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Simple UI: status, VPN toggle, share debug log, advanced (full v2rayNG UI).
 */
class SimpleMainActivity : HelperBaseActivity() {

    companion object {
        const val EXTRA_FROM_SIMPLE = "from_simple"
    }

    private lateinit var binding: ActivitySimpleMainBinding
    private val mainViewModel: MainViewModel by viewModels()
    private var lastRunningLogged: Boolean? = null

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startV2Ray()
        } else {
            binding.switchConnect.isChecked = false
            toast(R.string.toast_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BabukVpnBootstrap.applySimpleModeDefaults(this)
        BabukDebugLog.log(this, "SimpleMain", "onCreate")
        mainViewModel.initAssets(assets)
        mainViewModel.startListenBroadcast()

        mainViewModel.isRunning.observe(this) { running ->
            if (lastRunningLogged != running) {
                lastRunningLogged = running
                BabukDebugLog.log(this, "SimpleMain", "isRunning=$running ${Utils.vpnUiDiagnostics(this)}")
            }
            binding.switchConnect.setOnCheckedChangeListener(null)
            binding.switchConnect.isChecked = running
            binding.switchConnect.setOnCheckedChangeListener { _, isChecked ->
                onConnectSwitch(isChecked)
            }
            binding.tvStatus.text = if (running) {
                getString(R.string.babuk_simple_status_on)
            } else {
                getString(R.string.babuk_simple_status_off)
            }
        }

        binding.switchConnect.setOnCheckedChangeListener { _, isChecked ->
            onConnectSwitch(isChecked)
        }

        binding.btnShareLog.setOnClickListener {
            val send = BabukDebugLog.buildShareIntent(this)
            if (send != null) {
                startActivity(Intent.createChooser(send, getString(R.string.babuk_share_debug_log)))
            } else {
                toast(R.string.babuk_share_debug_failed)
            }
        }

        binding.btnAdvanced.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_SIMPLE, true))
        }

        lifecycleScope.launch {
            binding.tvStatus.text = getString(R.string.babuk_simple_status_refreshing)
            BabukVpnBootstrap.refreshServersAndSelectBest(this@SimpleMainActivity)
            binding.tvStatus.text = if (MmkvManager.getSelectServer().isNullOrBlank()) {
                getString(R.string.babuk_simple_status_no_servers)
            } else {
                getString(R.string.babuk_simple_status_ready)
            }
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.queryServiceRunningState()
    }

    private fun onConnectSwitch(isChecked: Boolean) {
        if (mainViewModel.isRunning.value == isChecked) return

        if (!isChecked) {
            V2RayServiceManager.stopVService(this)
            return
        }

        if (MmkvManager.getSelectServer().isNullOrBlank()) {
            binding.switchConnect.isChecked = false
            toast(R.string.babuk_simple_no_profile)
            return
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
            proceedWithVpnOrProxy()
        }
    }

    private fun proceedWithVpnOrProxy() {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        V2RayServiceManager.startVService(this)
    }
}
