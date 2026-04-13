package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.v2ray.ang.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File log in cache for sharing; same as 1.0.3-babuk builds. */
object BabukDebugLog {

    private const val TAG = "BabukDebugLog"
    const val FILE_NAME = "babuk_debug.txt"
    private const val MAX_BYTES = 512_000
    private const val KEEP_AFTER_TRIM = 400_000
    private const val MAX_EXPORT_COPIES = 18
    private val lock = Any()
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var sessionHeaderWritten = false

    fun log(context: Context, tag: String, message: String) {
        val line = "${tsFormat.format(Date())} [$tag] $message\n"
        Log.i(tag, message)
        synchronized(lock) {
            try {
                val dir = context.applicationContext.cacheDir
                val f = File(dir, FILE_NAME)
                ensureSessionHeader(context.applicationContext, f)
                if (f.exists() && f.length() > MAX_BYTES) {
                    trimTail(f)
                }
                f.appendText(line, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
            }
        }
    }

    private fun ensureSessionHeader(context: Context, f: File) {
        if (sessionHeaderWritten) return
        sessionHeaderWritten = true
        val header = buildString {
            append("--- babuKVN debug session ---\n")
            append("versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE} buildType=${BuildConfig.BUILD_TYPE}\n")
            append("applicationId=${BuildConfig.APPLICATION_ID} pid=${android.os.Process.myPid()}\n")
            append("--- end header ---\n")
        }
        try {
            if (!f.exists()) {
                f.writeText(header, Charsets.UTF_8)
            } else {
                f.appendText("\n$header", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "session header failed", e)
        }
    }

    private fun trimTail(f: File) {
        try {
            val bytes = f.readBytes()
            val cut = bytes.size - KEEP_AFTER_TRIM
            if (cut <= 0) return
            val header = "\n--- trimmed ${cut} bytes ---\n".toByteArray(Charsets.UTF_8)
            val tail = bytes.copyOfRange(cut, bytes.size)
            f.writeBytes(header + tail)
        } catch (e: Exception) {
            Log.w(TAG, "trim failed", e)
        }
    }

    private fun pruneOldExports(cacheDir: File) {
        try {
            val exports = cacheDir.listFiles { child ->
                child.isFile && child.name.startsWith("babuk_debug_") && child.name.endsWith(".txt") && child.name != FILE_NAME
            } ?: return
            if (exports.size <= MAX_EXPORT_COPIES) return
            exports.sortedBy { it.lastModified() }.take(exports.size - MAX_EXPORT_COPIES).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun buildShareIntent(context: Context): Intent? {
        return try {
            val app = context.applicationContext
            val cacheDir = app.cacheDir
            val f = File(cacheDir, FILE_NAME)
            if (!f.exists()) {
                log(context, "BabukDebug", "share requested but log empty; stub line")
            }
            val stamp = fileStampFormat.format(Date())
            val shareName = "babuk_debug_v${BuildConfig.VERSION_CODE}_$stamp.txt"
            val shareFile = File(cacheDir, shareName)
            f.copyTo(shareFile, overwrite = true)
            pruneOldExports(cacheDir)
            val uri = FileProvider.getUriForFile(app, BuildConfig.APPLICATION_ID + ".cache", shareFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, shareName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(app.contentResolver, shareName, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "share intent failed", e)
            null
        }
    }
}
