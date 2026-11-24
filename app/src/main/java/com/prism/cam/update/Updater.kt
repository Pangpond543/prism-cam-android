package com.prism.cam.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.prism.cam.BuildConfig
import com.prism.cam.PrismLog
import com.prism.cam.i18n.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub updater — same behaviour as the desktop suite: check `releases/latest` of the app's repo, show
 * "Version vX is available (you have vY)" + Update now → download the APK (progress) → check it is signed with the
 * same Prism key → Android's installer takes over. Only GitHub hosts, https only. Nothing else talks to the internet.
 */
object Updater {
    const val REPO = "Pangpond543/prism-cam-android"
    private val allowedHosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object Latest : State()
        data class Available(val version: String, val apkUrl: String) : State()
        data class Downloading(val version: String) : State()
        data class Ready(val version: String, val file: File) : State()
        data class Failed(val why: String) : State()
    }

    var state by mutableStateOf<State>(State.Idle); private set
    var progress by mutableFloatStateOf(0f); private set
    /** newer version seen by the quiet background check (dot on "…") */
    var nudge by mutableStateOf<String?>(null); private set

    private fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }.let { it + List(3 - it.size) { 0 } }
        val x = parts(a); val y = parts(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        require(u.protocol == "https" && u.host in allowedHosts) { "host" }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrismCam-Android")
            instanceFollowRedirects = true
        }
    }

    suspend fun check(quiet: Boolean = false) {
        if (!quiet) state = State.Checking
        val r = withContext(Dispatchers.IO) {
            try {
                val c = open("https://api.github.com/repos/$REPO/releases/latest")
                when (c.responseCode) {
                    404 -> State.Failed(T("ยังไม่มีเวอร์ชันบน GitHub"))
                    200 -> {
                        val j = JSONObject(c.inputStream.bufferedReader().readText())
                        val tag = j.optString("tag_name").trimStart('v')
                        val assets = j.optJSONArray("assets")
                        var apk: String? = null
                        if (assets != null) for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
                        }
                        when {
                            tag.isEmpty() -> State.Failed(T("อ่านเวอร์ชันไม่ได้"))
                            !newer(tag, BuildConfig.VERSION_NAME) -> State.Latest
                            apk == null -> State.Failed(T("เวอร์ชันใหม่ยังไม่มีไฟล์ APK"))
                            else -> State.Available(tag, apk)
                        }
                    }
                    else -> State.Failed(T("GitHub ตอบกลับผิดปกติ") + " (${c.responseCode})")
                }
            } catch (t: Throwable) {
                PrismLog.i("UPD check failed ${t.javaClass.simpleName} ${t.message}")
                State.Failed(T("เชื่อมต่ออินเทอร์เน็ตไม่ได้"))
            }
        }
        PrismLog.i("UPD check → $r")
        if (r is State.Available) nudge = r.version
        if (!quiet || r is State.Available) state = r
    }

    suspend fun download(ctx: Context) {
        val a = state as? State.Available ?: return
        state = State.Downloading(a.version); progress = 0f
        val r = withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "Prism-Cam-${a.version}.apk")
                val c = open(a.apkUrl)
                val total = c.contentLengthLong.coerceAtLeast(1)
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            progress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                if (!sameSigner(ctx, f)) { f.delete(); State.Failed(T("ไฟล์อัปเดตไม่ได้เซ็นด้วยกุญแจของ Prism Cam — ไม่ติดตั้ง")) }
                else State.Ready(a.version, f)
            } catch (t: Throwable) {
                PrismLog.i("UPD download failed ${t.message}")
                State.Failed(T("ดาวน์โหลดไม่สำเร็จ"))
            }
        }
        state = r
        if (r is State.Ready) install(ctx, r.file)
    }

    fun install(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** the downloaded APK must carry the same signing certificate as the installed app */
    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, f: File): Boolean = try {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val theirs = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            mine != null && mine == theirs
        } else {
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toCharsString() }?.toSet()
            val theirs = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES)?.signatures?.map { it.toCharsString() }?.toSet()
            mine != null && mine == theirs
        }
    } catch (_: Throwable) { false }
}
