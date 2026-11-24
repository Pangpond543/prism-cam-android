package com.prism.cam.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.prism.cam.PrismLog
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismBackground
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismFont
import com.prism.cam.ui.theme.PrismThemeAccess
import java.util.concurrent.Executors

/**
 * Phase 6 — "send to Prism Cam on the PC". The PC app already serves a phone-camera page (WebRTC, same Wi-Fi, pairing
 * code in the link, self-signed HTTPS). Here: scan the QR that Prism Cam on the PC shows (or paste the link), then run
 * that page inside the app with the camera handed over to it. Only private-network hosts are accepted; the PC's
 * certificate is pinned after the first connection.
 */
object PcLinkStore {
    fun last(ctx: Context): String? = ctx.getSharedPreferences("prism", Context.MODE_PRIVATE).getString("pc.last", null)
    fun save(ctx: Context, url: String) = ctx.getSharedPreferences("prism", Context.MODE_PRIVATE).edit().putString("pc.last", url).apply()
    fun pin(ctx: Context, host: String): String? = ctx.getSharedPreferences("prism", Context.MODE_PRIVATE).getString("pc.cert.$host", null)
    fun savePin(ctx: Context, host: String, sha: String) = ctx.getSharedPreferences("prism", Context.MODE_PRIVATE).edit().putString("pc.cert.$host", sha).apply()

    /** a Prism Cam phone link: https://<private address>:<port>/?k=<code> */
    fun valid(url: String): Boolean {
        val u = try { Uri.parse(url.trim()) } catch (_: Throwable) { return false }
        if (u.scheme != "https") return false
        val h = u.host ?: return false
        if (u.getQueryParameter("k").isNullOrBlank()) return false
        return isPrivate(h)
    }

    fun isPrivate(h: String): Boolean {
        if (h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }
}

@Composable
fun PcLinkScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    BackHandler { if (url != null) url = null else onClose() }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val u = url
    if (u == null) ScanStep(onFound = { found -> PcLinkStore.save(ctx, found); PrismLog.i("PC link ${Uri.parse(found).host}"); url = found }, onClose = onClose)
    else WebStep(u, onClose = { url = null })
}

@Composable
private fun ScanStep(onFound: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val c = PrismThemeAccess.colors
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val last = remember { PcLinkStore.last(ctx) }
    val pv = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // QR scanner: preview + luminance frames → ZXing
    DisposableEffect(Unit) {
        val exec = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var done = false
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get(); provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
            analysis.setAnalyzer(exec) { img ->
                img.use {
                    if (done) return@use
                    val plane = it.planes[0]
                    val buf = plane.buffer
                    val data = ByteArray(buf.remaining()).also { a -> buf.get(a) }
                    val src = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                    val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text } catch (_: Throwable) { null } finally { reader.reset() }
                    if (text != null) {
                        if (PcLinkStore.valid(text)) { done = true; ContextCompat.getMainExecutor(ctx).execute { onFound(text.trim()) } }
                        else ContextCompat.getMainExecutor(ctx).execute { error = T("QR นี้ไม่ใช่ลิงก์ของ Prism Cam บนคอม") }
                    }
                }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) { PrismLog.i("ERR qr bind ${t.message}") }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { done = true; provider?.unbindAll(); exec.shutdown() }
    }
    Box(Modifier.fillMaxSize()) {
        PrismBackground()
        Column(
            Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp).widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PrismText(T("ส่งภาพเข้า Prism Cam บนคอม"), style = PrismThemeAccess.type.title)
            PrismText(T("บนคอม: Prism Cam → มือถือเป็นกล้อง (สแกน QR) แล้วส่องกล้องไปที่ QR · มือถือกับคอมต้องอยู่ Wi-Fi เดียวกัน"), style = PrismThemeAccess.type.caption, color = c.ink2)
            Box(
                Modifier.size(260.dp).prismPane(c, RoundedCornerShape(28.dp), strong = true).padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView({ pv }, Modifier.fillMaxSize().border(0.dp, Color.Transparent))
                Box(Modifier.size(170.dp).border(2.dp, Prism.grad, RoundedCornerShape(18.dp)))
            }
            error?.let { PrismText(it, style = PrismThemeAccess.type.caption, color = Prism.danger) }
            // paste / type the link instead
            Row(
                Modifier.fillMaxWidth().prismPane(c, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (typed.isEmpty()) PrismText("https://192.168.…:8543/?k=…", style = PrismThemeAccess.type.caption, color = c.ink3)
                    BasicTextField(typed, { typed = it; error = null }, singleLine = true, textStyle = TextStyle(fontFamily = PrismFont, fontSize = 14.sp, color = c.ink), cursorBrush = SolidColor(c.ink))
                }
                Spacer(Modifier.size(8.dp))
                PrismPill(T("เชื่อมต่อ"), {
                    if (PcLinkStore.valid(typed)) onFound(typed.trim()) else error = T("ลิงก์ต้องเป็น https:// ของคอมในบ้าน และมี ?k=")
                }, kind = PillKind.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (last != null) PrismPill(T("เชื่อมต่อคอมเครื่องเดิม"), { onFound(last) }, icon = "refresh")
                PrismPill(T("กลับไปที่กล้อง"), onClose, icon = "camera")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebStep(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val host = remember(url) { Uri.parse(url).host ?: "" }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                WebView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.host != host
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                            // The PC uses its own certificate. Accept it only for the private address from the QR, and pin it.
                            val certHost = Uri.parse(error.url).host
                            val sha = sha256(error.certificate)
                            val pinned = PcLinkStore.pin(ctx, host)
                            if (certHost == host && PcLinkStore.isPrivate(host) && (pinned == null || pinned == sha)) {
                                if (pinned == null && sha != null) PcLinkStore.savePin(ctx, host, sha)
                                handler.proceed()
                            } else {
                                PrismLog.i("PC cert refused host=$certHost pinned=${pinned != null}")
                                handler.cancel()
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val ok = request.origin.host == host &&
                                request.resources.all { r -> r == PermissionRequest.RESOURCE_VIDEO_CAPTURE || r == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                            if (ok) request.grant(request.resources) else request.deny()
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            PrismPill(T("เลิกส่ง"), onClose, kind = PillKind.Warn, icon = "x")
        }
    }
}

/** fingerprint of the PC's certificate (Android 10+: the real certificate; older: its name + dates) */
private fun sha256(cert: android.net.http.SslCertificate?): String? = try {
    val bytes = if (android.os.Build.VERSION.SDK_INT >= 29) cert?.x509Certificate?.encoded
    else cert?.let { "${it.issuedTo?.dName}|${it.issuedBy?.dName}|${it.validNotBeforeDate?.time}|${it.validNotAfterDate?.time}".toByteArray() }
    bytes?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
} catch (_: Throwable) { null }

