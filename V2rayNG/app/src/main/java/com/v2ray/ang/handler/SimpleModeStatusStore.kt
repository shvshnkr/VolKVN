package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible status line for simple mode (Dahusim [DataStore.simpleModeActivity]).
 */
object SimpleModeStatusStore {

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun setActivity(text: String) {
        if (Utils.isVpnTransportActive(AngApplication.application) && V2RayServiceManager.isRunning()) {
            VolkvnDebugLog.simpleModeLog("H19", "activity_write_skipped_while_connected text=${text.take(48)}")
            return
        }
        _status.value = text
    }

    fun clearActivity() {
        _status.value = ""
    }

    fun setFromStringRes(context: Context, resId: Int) {
        setActivity(context.getString(resId))
    }

}
