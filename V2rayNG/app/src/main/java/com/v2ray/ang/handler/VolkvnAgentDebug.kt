package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import java.util.UUID
import org.json.JSONObject

/**
 * Debug-session NDJSON emitter for agent debugging.
 */
object VolkvnAgentDebug {

    private const val TAG = "VolkvnAgentDebug"
    private const val SESSION_ID = "a41dbf"

    fun emit(
        context: Context,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String? = null,
    ) {
        // #region agent log
        val ts = System.currentTimeMillis()
        val effectiveRunId = if (runId.isNullOrBlank()) "baseline" else runId
        val dataJson = JSONObject()
        for ((k, v) in data) {
            when (v) {
                null -> dataJson.put(k, JSONObject.NULL)
                is Number -> dataJson.put(k, v)
                is Boolean -> dataJson.put(k, v)
                else -> dataJson.put(k, v.toString())
            }
        }
        val root = JSONObject()
        root.put("sessionId", SESSION_ID)
        root.put("timestamp", ts)
        root.put("id", "log_${ts}_${UUID.randomUUID().toString().take(8)}")
        root.put("hypothesisId", hypothesisId)
        root.put("runId", effectiveRunId)
        root.put("location", location)
        root.put("message", message)
        root.put("data", dataJson)
        val line = root.toString()
        try {
            VolkvnDebugLog.log(context.applicationContext, "AGENT", line)
        } catch (e: Exception) {
            Log.w(TAG, "emit failed", e)
        }
        // #endregion
    }
}
