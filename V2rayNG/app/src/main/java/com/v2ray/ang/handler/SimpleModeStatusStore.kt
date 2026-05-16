package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible status line for simple mode (Dahusim [DataStore.simpleModeActivity]).
 */
object SimpleModeStatusStore {

    private const val PROGRESS_THROTTLE_MS = 300L

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    @VisibleForTesting
    var progressThrottleMs: Long = PROGRESS_THROTTLE_MS

    @VisibleForTesting
    var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    @VisibleForTesting
    var vpnTransportActive: (() -> Boolean)? = null

    @VisibleForTesting
    var coreRunning: (() -> Boolean)? = null

    @VisibleForTesting
    var lastProgressEmitAtMs: Long = 0L

    @VisibleForTesting
    var pendingProgressText: String? = null

    private fun isVpnCoreActive(): Boolean {
        val vpn = vpnTransportActive?.invoke()
            ?: Utils.isVpnTransportActive(AngApplication.application)
        val running = coreRunning?.invoke() ?: V2RayServiceManager.isRunning()
        return vpn && running
    }

    @VisibleForTesting
    fun shouldBlockBackgroundActivity(): Boolean = isVpnCoreActive()

    fun setActivity(text: String) {
        if (shouldBlockBackgroundActivity()) {
            VolkvnDebugLog.simpleModeLog("H19", "activity_write_skipped_while_connected text=${text.take(48)}")
            return
        }
        _status.value = text
    }

    fun clearActivity() {
        _status.value = ""
        pendingProgressText = null
    }

    fun setFromStringRes(context: Context, resId: Int) {
        setActivity(context.getString(resId))
    }

    fun setConnected(context: Context) {
        _status.value = context.getString(R.string.volkvn_simple_status_on)
        pendingProgressText = null
    }

    fun setProgress(
        context: Context,
        @StringRes stageResId: Int,
        done: Int,
        total: Int,
        force: Boolean = false,
    ) {
        val safeDone = done.coerceAtLeast(0)
        val safeTotal = total.coerceAtLeast(1)
        val cappedDone = safeDone.coerceAtMost(safeTotal)
        val text = context.getString(stageResId, cappedDone, safeTotal)
        val now = elapsedRealtimeMs()
        if (!force && now - lastProgressEmitAtMs < progressThrottleMs) {
            pendingProgressText = text
            return
        }
        lastProgressEmitAtMs = now
        pendingProgressText = null
        _status.value = text
    }

    @VisibleForTesting
    fun flushPendingProgress() {
        val text = pendingProgressText ?: return
        pendingProgressText = null
        lastProgressEmitAtMs = elapsedRealtimeMs()
        _status.value = text
    }

    @VisibleForTesting
    fun setStatusForTest(text: String) {
        _status.value = text
    }

    @VisibleForTesting
    fun emitProgressTextForTest(text: String, force: Boolean = false) {
        val now = elapsedRealtimeMs()
        if (!force && now - lastProgressEmitAtMs < progressThrottleMs) {
            pendingProgressText = text
            return
        }
        lastProgressEmitAtMs = now
        pendingProgressText = null
        _status.value = text
    }

    fun isConnectedLabel(line: String, context: Context): Boolean =
        line == context.getString(R.string.volkvn_simple_status_on)

    fun isStaleWhileConnected(line: String, context: Context): Boolean =
        line == context.getString(R.string.volkvn_status_connecting) ||
            line == context.getString(R.string.volkvn_status_verifying)

}
