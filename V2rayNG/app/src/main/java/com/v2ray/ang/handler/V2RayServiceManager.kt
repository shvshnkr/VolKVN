package com.v2ray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.service.V2RayProxyOnlyService
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object V2RayServiceManager {
    private const val WATCHDOG_INTERVAL_MS = 4 * 60 * 1000L
    private const val WATCHDOG_INITIAL_DELAY_MS = 90 * 1000L
    private const val WATCHDOG_FAILURE_THRESHOLD = 2
    private const val NETWORK_HANDOFF_GRACE_MS = 4_000L
    private const val NETWORK_HANDOFF_MIN_INTERVAL_MS = 30_000L

    private val watchdogScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private var networkRecoveryInProgress = false
    private var lastNetworkHandoffRecoveryAt = 0L

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        Log.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")
        VolkvnDebugLog.log(
            context,
            AppConfig.TAG,
            "startVService vpnMode=${SettingsManager.isVpnMode()} ${Utils.vpnUiDiagnostics(context)}",
        )

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        startContextService(context)
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        VolkvnDebugLog.log(context, AppConfig.TAG, "stopVService ${Utils.vpnUiDiagnostics(context)}")
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            context.toast(R.string.volkvn_err_no_server_selected)
            return
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            context.toast(R.string.volkvn_err_decode_config)
            return
        }

        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            context.toast(context.getString(R.string.volkvn_err_invalid_server_address, config.server.orEmpty()))
            return
        }
//        val result = V2rayConfigUtil.getV2rayConfig(context, guid)
//        if (!result.status) return

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isVpnMode = SettingsManager.isVpnMode()
        val intent = if (isVpnMode) {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start service", e)
            context.toast(context.getString(R.string.volkvn_err_start_foreground, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        fun fail(service: Service?, reason: String, error: Throwable? = null): Boolean {
            if (error == null) {
                Log.e(AppConfig.TAG, "StartCore-Manager: $reason")
            } else {
                Log.e(AppConfig.TAG, "StartCore-Manager: $reason", error)
            }
            if (service != null) {
                val msg = if (error == null) reason else "$reason: ${error.message ?: error.javaClass.simpleName}"
                VolkvnDebugLog.log(service, "StartCore", msg)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, msg)
            }
            return false
        }

        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return fail(getService(), "core already running")
        }

        val service = getService()
        if (service == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            return fail(service, "no server selected")
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            return fail(service, "failed to decode selected server")
        }

        Log.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            return fail(service, "failed to build V2Ray config")
        }

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            return fail(service, "registerReceiver failed", e)
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            return fail(service, "coreController.startLoop threw", e)
        }

        if (coreController.isRunning == false) {
            fail(service, "core did not enter running state")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
            startConnectionWatchdog(service)
            Log.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
        } catch (e: Exception) {
            return fail(service, "post-start notification/UI failed", e)
        }
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false
        stopConnectionWatchdog()

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()
        LocalSocksAuth.clear()
        try {
            File(service.filesDir, AppConfig.LOCAL_SOCKS_UNIX_FILENAME).delete()
            File(service.filesDir, AppConfig.LOCAL_HTTP_UNIX_FILENAME).delete()
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "local inbound socket cleanup", e)
        }

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    private fun startConnectionWatchdog(service: Service) {
        stopConnectionWatchdog()
        watchdogJob = watchdogScope.launch {
            var consecutiveFailures = 0
            delay(WATCHDOG_INITIAL_DELAY_MS)
            while (isActive) {
                if (!coreController.isRunning) {
                    delay(10_000)
                    continue
                }
                val (ok, detail) = probeCoreHealth()
                if (ok) {
                    if (consecutiveFailures > 0) {
                        VolkvnDebugLog.log(service, "Watchdog", "health restored")
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                    VolkvnDebugLog.log(
                        service,
                        "Watchdog",
                        "health probe failed ($consecutiveFailures/$WATCHDOG_FAILURE_THRESHOLD): $detail",
                    )
                    if (consecutiveFailures >= WATCHDOG_FAILURE_THRESHOLD) {
                        val failedGuid = MmkvManager.getSelectServer()
                        val targetSubId = currentConfig?.subscriptionId ?: AppConfig.VOLKVN_SUBSCRIPTION_ID
                        VolkvnDebugLog.log(service, "Watchdog", "auto-recover: reselect + restart (reason=$detail)")
                        VolkvnServerSelector.markServerUnhealthy(failedGuid, "watchdog:$detail")
                        runCatching {
                            VolkvnServerSelector.pickBestServer(service, targetSubId)
                        }
                        withContext(Dispatchers.Main) {
                            stopVService(service)
                            delay(700)
                            startVService(service)
                        }
                        consecutiveFailures = 0
                    }
                }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun stopConnectionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun onUnderlyingNetworkChanged(reason: String) {
        val service = getService() ?: return
        if (!coreController.isRunning) return
        val now = System.currentTimeMillis()
        if (networkRecoveryInProgress || now - lastNetworkHandoffRecoveryAt < NETWORK_HANDOFF_MIN_INTERVAL_MS) {
            return
        }
        networkRecoveryInProgress = true
        lastNetworkHandoffRecoveryAt = now
        watchdogScope.launch {
            try {
                delay(NETWORK_HANDOFF_GRACE_MS)
                val (ok, detail) = probeCoreHealth()
                if (!ok) {
                    VolkvnDebugLog.log(service, "Watchdog", "network handoff recover ($reason): $detail")
                    withContext(Dispatchers.Main) {
                        stopVService(service)
                        delay(700)
                        startVService(service)
                    }
                } else {
                    VolkvnDebugLog.log(service, "Watchdog", "network handoff healthy ($reason): $detail")
                }
            } finally {
                networkRecoveryInProgress = false
            }
        }
    }

    private fun probeCoreHealth(): Pair<Boolean, String> {
        return runCatching {
            val primary = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            if (primary >= 0) return true to "ok($primary)"
            val fallback = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
            if (fallback >= 0) {
                true to "ok-fallback($fallback)"
            } else {
                false to "delay=-1"
            }
        }.getOrElse { e ->
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Queries the statistics for a given tag and link.
     * @param tag The tag to query.
     * @param link The link to query.
     * @return The statistics value.
     */
    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}