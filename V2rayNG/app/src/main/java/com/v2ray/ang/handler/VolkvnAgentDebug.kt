package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Debug-session NDJSON (session fa7510) — только в общий [VolkvnDebugLog] (тег AGENT), строка = один JSON.
 */
object VolkvnAgentDebug {

    private const val TAG = "VolkvnAgentDebug"
    const val SESSION_ID = "fa7510"

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
        root.put("hypothesisId", hypothesisId)
        root.put("location", location)
        root.put("message", message)
        root.put("data", dataJson)
        if (!runId.isNullOrBlank()) root.put("runId", runId)
        val line = root.toString()
        try {
            VolkvnDebugLog.log(context.applicationContext, "AGENT", line)
        } catch (e: Exception) {
            Log.w(TAG, "emit failed", e)
        }
        // #endregion
    }
}
