package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySimpleMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.SimpleModeStatusStore
import com.v2ray.ang.handler.VolkvnAgentDebug
import com.v2ray.ang.handler.VolkvnDebugLog
import com.v2ray.ang.handler.VolkvnDefaultUserBootstrap
import com.v2ray.ang.handler.VolkvnSimpleModeConnectOrchestrator
import com.v2ray.ang.handler.VolkvnSimpleModeNetwork
import com.v2ray.ang.handler.VolkvnVpnBootstrap
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Simple UI: status, VPN toggle, share debug log, advanced (full v2rayNG UI).
 */
class SimpleMainActivity : HelperBaseActivity() {

    companion object {
        const val EXTRA_FROM_SIMPLE = "from_simple"
        private const val PRECONNECT_REFRESH_MIN_INTERVAL_MS = 45_000L
    }

    private lateinit var binding: ActivitySimpleMainBinding
    private val mainViewModel: MainViewModel by viewModels()
    private var lastRunningLogged: Boolean? = null
    private var initialPoolRefreshDone = false
    private var preconnectRefreshInProgress = false

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startV2RayWithPreflight()
        } else {
            binding.switchConnect.isChecked = false
            toast(R.string.toast_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        VolkvnVpnBootstrap.applySimpleModeDefaults(this)
        VolkvnDebugLog.log(this, "SimpleMain", "onCreate")
        mainViewModel.initAssets(assets)
        mainViewModel.startListenBroadcast()

        mainViewModel.isRunning.observe(this) { running ->
            if (lastRunningLogged != running) {
                lastRunningLogged = running
                VolkvnDebugLog.log(this, "SimpleMain", "isRunning=$running ${Utils.vpnUiDiagnostics(this)}")
            }
            binding.switchConnect.setOnCheckedChangeListener(null)
            binding.switchConnect.isChecked = running
            binding.switchConnect.setOnCheckedChangeListener { _, isChecked ->
                onConnectSwitch(isChecked)
            }
            if (running) {
                val activity = SimpleModeStatusStore.status.value
                binding.tvStatus.text = activity.ifBlank {
                    getString(R.string.volkvn_simple_status_on)
                }
            } else if (SimpleModeStatusStore.status.value.isBlank()) {
                binding.tvStatus.text = getString(R.string.volkvn_simple_status_off)
            }
        }

        lifecycleScope.launch {
            SimpleModeStatusStore.status.collectLatest { line ->
                if (mainViewModel.isRunning.value == true) {
                    binding.tvStatus.text = line.ifBlank {
                        getString(R.string.volkvn_simple_status_on)
                    }
                } else if (line.isNotBlank()) {
                    binding.tvStatus.text = line
                }
            }
        }

        binding.switchConnect.setOnCheckedChangeListener { _, isChecked ->
            onConnectSwitch(isChecked)
        }

        binding.btnShareLog.setOnClickListener {
            val send = VolkvnDebugLog.buildShareIntent(this)
            if (send != null) {
                startActivity(Intent.createChooser(send, getString(R.string.volkvn_share_debug_log)))
            } else {
                toast(R.string.volkvn_share_debug_failed)
            }
        }

        binding.btnAdvanced.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_FROM_SIMPLE, true))
        }

        lifecycleScope.launch {
            VolkvnDefaultUserBootstrap.bootstrapAll(this@SimpleMainActivity)
            VolkvnSimpleModeNetwork.probeAndApply(this@SimpleMainActivity)
            SimpleModeStatusStore.setFromStringRes(this@SimpleMainActivity, R.string.volkvn_status_refreshing_subs)
            VolkvnVpnBootstrap.refreshServersAndSelectBest(this@SimpleMainActivity)
            SimpleModeStatusStore.clearActivity()
            binding.tvStatus.text = if (MmkvManager.getSelectServer().isNullOrBlank()) {
                getString(R.string.volkvn_simple_status_no_servers)
            } else {
                getString(R.string.volkvn_simple_status_ready)
            }
            initialPoolRefreshDone = true
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }
        maybePromptBatteryOptimizationExemption()
    }

    override fun onResume() {
        super.onResume()
        // #region agent log
        VolkvnAgentDebug.emit(
            this,
            hypothesisId = "H9",
            location = "SimpleMainActivity.kt:onResume",
            message = "ui_resumed",
            data = mapOf(
                "switchChecked" to binding.switchConnect.isChecked,
                "runningLive" to (mainViewModel.isRunning.value ?: false),
            ),
        )
        // #endregion
        mainViewModel.queryServiceRunningState()
    }

    override fun onPause() {
        // #region agent log
        VolkvnAgentDebug.emit(
            this,
            hypothesisId = "H9",
            location = "SimpleMainActivity.kt:onPause",
            message = "ui_paused",
            data = mapOf(
                "switchChecked" to binding.switchConnect.isChecked,
                "runningLive" to (mainViewModel.isRunning.value ?: false),
            ),
        )
        // #endregion
        super.onPause()
    }

    private fun onConnectSwitch(isChecked: Boolean) {
        val runningLive = mainViewModel.isRunning.value == true
        val transportActive = Utils.isVpnTransportActive(this)
        // #region agent log
        VolkvnAgentDebug.emit(
            this,
            hypothesisId = "H9",
            location = "SimpleMainActivity.kt:onConnectSwitch",
            message = "connect_switch_toggled",
            data = mapOf(
                "isChecked" to isChecked,
                "isRunningLive" to runningLive,
                "transportActive" to transportActive,
                "initialPoolRefreshDone" to initialPoolRefreshDone,
                "selectedGuidLen" to (MmkvManager.getSelectServer()?.length ?: 0),
            ),
        )
        // #endregion
        if (isChecked && runningLive && transportActive) {
            // #region agent log
            VolkvnAgentDebug.emit(
                this,
                hypothesisId = "H16",
                location = "SimpleMainActivity.kt:onConnectSwitch",
                message = "early_return_already_running",
                data = mapOf(
                    "isChecked" to isChecked,
                    "isRunningLive" to runningLive,
                    "transportActive" to transportActive,
                ),
            )
            // #endregion
            return
        }
        if (!isChecked && !runningLive && !transportActive) {
            // #region agent log
            VolkvnAgentDebug.emit(
                this,
                hypothesisId = "H16",
                location = "SimpleMainActivity.kt:onConnectSwitch",
                message = "early_return_already_stopped",
                data = mapOf(
                    "isChecked" to isChecked,
                    "isRunningLive" to runningLive,
                    "transportActive" to transportActive,
                ),
            )
            // #endregion
            return
        }

        if (!isChecked) {
            V2RayServiceManager.stopVService(this)
            return
        }

        if (!initialPoolRefreshDone) {
            binding.switchConnect.isChecked = false
            toast(R.string.volkvn_simple_status_refreshing)
            return
        }

        if (MmkvManager.getSelectServer().isNullOrBlank()) {
            binding.switchConnect.isChecked = false
            toast(R.string.volkvn_simple_no_profile)
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
                startV2RayWithPreflight()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2RayWithPreflight()
        }
    }

    private fun startV2RayWithPreflight() {
        lifecycleScope.launch {
            if (preconnectRefreshInProgress) {
                V2RayServiceManager.startVService(this@SimpleMainActivity)
                return@launch
            }
            val now = System.currentTimeMillis()
            val lastGlobalPoolRefreshAt =
                MmkvManager.decodeSettingsLong(AppConfig.PREF_VOLKVN_LAST_POOL_REFRESH_AT, 0L)
            val needRefresh = now - lastGlobalPoolRefreshAt >= PRECONNECT_REFRESH_MIN_INTERVAL_MS
            if (needRefresh) {
                preconnectRefreshInProgress = true
                runCatching {
                    VolkvnVpnBootstrap.refreshServersAndSelectBest(this@SimpleMainActivity)
                }.onFailure {
                    VolkvnDebugLog.log(
                        this@SimpleMainActivity,
                        "SimpleMain",
                        "preconnect refresh failed: ${it.message ?: it.javaClass.simpleName}",
                    )
                }
                preconnectRefreshInProgress = false
            }
            val started = VolkvnSimpleModeConnectOrchestrator.connect(this@SimpleMainActivity) {
                V2RayServiceManager.startVService(this@SimpleMainActivity)
            }
            if (!started) {
                binding.switchConnect.isChecked = false
            }
        }
    }

    private fun maybePromptBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations()) return
        val prompted = MmkvManager.decodeSettingsBool(AppConfig.PREF_VOLKVN_BATTERY_OPT_PROMPTED, false)
        if (prompted) return

        AlertDialog.Builder(this)
            .setTitle(R.string.volkvn_battery_opt_title)
            .setMessage(R.string.volkvn_battery_opt_message)
            .setCancelable(true)
            .setPositiveButton(R.string.volkvn_battery_opt_allow) { _, _ ->
                MmkvManager.encodeSettings(AppConfig.PREF_VOLKVN_BATTERY_OPT_PROMPTED, true)
                openBatteryOptimizationRequest()
            }
            .setNeutralButton(R.string.volkvn_battery_opt_open_settings) { _, _ ->
                MmkvManager.encodeSettings(AppConfig.PREF_VOLKVN_BATTERY_OPT_PROMPTED, true)
                openBatteryOptimizationSettings()
            }
            .setNegativeButton(R.string.volkvn_battery_opt_later) { _, _ ->
                MmkvManager.encodeSettings(AppConfig.PREF_VOLKVN_BATTERY_OPT_PROMPTED, true)
            }
            .show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            openBatteryOptimizationSettings()
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
        val opened = intents.firstOrNull { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        } != null
        if (!opened) {
            toast(R.string.volkvn_battery_opt_open_failed)
        }
    }
}
