package com.prism.cam

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local log, never sent anywhere. File: /sdcard/Android/data/com.prism.cam/files/prism.log (readable over USB:
 * `adb shell cat /sdcard/Android/data/com.prism.cam/files/prism.log`) + logcat tag "PrismCam".
 * Every start writes `START ver=… code=… sdk=… device=…` so we can prove which build the phone runs.
 * Written on a background thread; capped at 512 KB (older half dropped).
 */
object PrismLog {
    private const val TAG = "PrismCam"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "prism-log").apply { isDaemon = true } }
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun start(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, "prism.log")
        i("START ver=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /** start-up timing: "T+<ms since process start> <label>" (only the first 20 marks) */
    private var marks = 0
    fun mark(label: String) {
        if (marks++ >= 20) return
        i("T+${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} $label")
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        val line = "${fmt.format(Date())} $msg\n"
        io.execute {
            try {
                if (f.length() > 512 * 1024) f.writeText(f.readText().takeLast(256 * 1024))
                f.appendText(line)
            } catch (_: Throwable) { }
        }
    }
}
