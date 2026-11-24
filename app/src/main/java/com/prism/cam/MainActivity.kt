package com.prism.cam

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.prism.cam.camera.PrismCamera
import com.prism.cam.i18n.T
import com.prism.cam.prefs.PrismPrefs
import com.prism.cam.ui.CameraScreen
import com.prism.cam.ui.FxSheet
import com.prism.cam.ui.TextEditor
import com.prism.cam.ui.KitScreen
import com.prism.cam.ui.MenuSheet
import com.prism.cam.ui.PermissionScreen
import com.prism.cam.ui.kit.PrismToast
import com.prism.cam.ui.theme.Look
import com.prism.cam.ui.theme.PrismTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NEW_TEXT = Any()

class MainActivity : ComponentActivity() {
    private val cam by lazy { PrismCamera(applicationContext) }
    private val vision by lazy { com.prism.cam.ai.Vision(applicationContext, cam) }

    private var camGranted by mutableStateOf(false)
    private var camBlocked by mutableStateOf(false)
    private var menuOpen by mutableStateOf(false)
    private var fxOpen by mutableStateOf(false)
    private var pcOpen by mutableStateOf(false)
    /** text editor: null = closed, Scene.Text or NEW_TEXT */
    private var textEdit by mutableStateOf<Any?>(null)
    private var showKit by mutableStateOf(false)
    private var testOrient by mutableStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    private var cleanReq by mutableStateOf<Int?>(null)
    private var toast by mutableStateOf<Pair<String, Boolean>?>(null)
    private var toastId = 0

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        camGranted = has(Manifest.permission.CAMERA)
        camBlocked = !camGranted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        PrismLog.i("PERM camera=$camGranted blocked=$camBlocked $res")
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cam.audioOk = ok
        PrismLog.i("PERM mic=$ok")
        if (!ok) say(T("ไม่ได้อนุญาตไมค์ วิดีโอจะไม่มีเสียง"), false)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun say(text: String, ok: Boolean = true) {
        toast = text to ok
        val id = ++toastId
        lifecycleScope.launch { delay(2200); if (toastId == id) toast = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrismLog.mark("activity")
        camGranted = has(Manifest.permission.CAMERA)
        cam.audioOk = has(Manifest.permission.RECORD_AUDIO)
        cam.onMessage = { t, ok -> say(t, ok) }
        com.prism.cam.effects.Scene.init(this)
        // photos with effects: same look + scene as the preview, drawn on the full-resolution still
        cam.photoFx = {
            val items = com.prism.cam.effects.Scene.items.toList()
            val frame = com.prism.cam.effects.Scene.frame
            com.prism.cam.effects.PhotoFx.Job(
                look = cam.look,
                overlay = if (com.prism.cam.effects.Scene.isEmpty) null else { bmp ->
                    val sticker = com.prism.cam.effects.Scene.faceSticker
                    val faces = if (sticker != null) vision.photoFaces(bmp) else emptyList()
                    com.prism.cam.effects.Scene.render(android.graphics.Canvas(bmp), bmp.width, bmp.height, items, frame, faces, sticker)
                },
                background = cam.photoBackground?.invoke(),
                rotDisplay = cam.displaySensorRotation(),
                mirror = cam.front,
            )
        }
        if (camGranted) cam.start(this) else askCameraNow()
        runActs(intent)
        addOnNewIntentListener { setIntent(it); runActs(it) }
        watchHeat()
        // quiet update check: 4 s after start, then every 6 h (dot on "…" when a newer version exists)
        lifecycleScope.launch { delay(4000); while (true) { com.prism.cam.update.Updater.check(quiet = true); delay(6 * 3600_000L) } }
        // portrait mode = photo + AI background blur
        lifecycleScope.launch { androidx.compose.runtime.snapshotFlow { cam.mode }.collect { vision.usePortrait(it == PrismCamera.Mode.Portrait) } }
        enableEdgeToEdge()
        setContent {
            val dark = PrismPrefs.dark
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            PrismTheme(dark = dark, look = PrismPrefs.look) {
                Box(Modifier.fillMaxSize()) {
                    when {
                        showKit -> KitScreen()
                        pcOpen && camGranted -> com.prism.cam.ui.PcLinkScreen { pcOpen = false }
                        !camGranted -> PermissionScreen(camBlocked, onAllow = { askCameraNow() }, onSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        })
                        else -> CameraScreen(cam, onMenu = { menuOpen = true }, onFx = { fxOpen = true }, onEditText = { textEdit = it }, onVideoMode = {
                            if (!has(Manifest.permission.RECORD_AUDIO)) askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }, freeOrientation = testOrient, cleanRequest = cleanReq)
                    }
                    if (camGranted && !showKit) FxSheet(fxOpen, cam, onClose = { fxOpen = false }, onNewText = { fxOpen = false; textEdit = NEW_TEXT }, extraTabs = aiTabs())
                    textEdit?.let { t -> TextEditor(t as? com.prism.cam.effects.Scene.Text) { textEdit = null } }
                    MenuSheet(menuOpen, onClose = { menuOpen = false }, onCameraChange = { cam.applyShape() }, onSendToPc = { menuOpen = false; cam.lendCamera(); pcOpen = true })
                    val t = toast
                    AnimatedVisibility(
                        t != null,
                        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp),
                        enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
                    ) { PrismToast(t?.first ?: "", t?.second ?: true) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // came back from Settings after allowing the camera there
        if (!camGranted && has(Manifest.permission.CAMERA)) { camGranted = true; camBlocked = false }
        cam.audioOk = has(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        if (isFinishing) { cam.release(); vision.release() }
        super.onDestroy()
    }

    private fun askCameraNow() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        askCamera.launch(list.toTypedArray())
    }

    private fun aiTabs() = com.prism.cam.ui.aiTabs(vision) {
        pickBackground.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val pickBackground = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { s ->
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeStream(s, null, o)
                        o
                    }?.let { o ->
                        var sample = 1
                        while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
                        contentResolver.openInputStream(uri)?.use { s -> android.graphics.BitmapFactory.decodeStream(s, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                    }
                } catch (t: Throwable) { null }
            }
            if (bmp != null) vision.useBgId("photo", bmp) else say(T("เปิดรูปนี้ไม่ได้"), false)
        }
    }

    /** Volume keys = shutter (photo, or start / stop video). Held keys don't repeat. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) &&
            camGranted && !menuOpen && !showKit && textEdit == null && !pcOpen
        ) {
            if (event.repeatCount == 0) cam.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Thermal guard (docs/plan.md): log every level, tell the user when the phone is getting hot. */
    private fun watchHeat() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(this)) { st ->
            PrismLog.i("HEAT status=$st")
            if (st >= PowerManager.THERMAL_STATUS_SEVERE) say(T("เครื่องร้อนมาก พักกล้องสักครู่จะดีกว่า"), false)
        }
    }

    /**
     * Test hook (like the desktop PRISM_ACT): comma list in the "act" extra, run in order once the picture is live:
     * `adb shell am start -n com.prism.cam/.MainActivity --es act dark,glass,en,photo,video:3`.
     * Looks: dark | light | glass | classic | th | en (persist — finish with light,classic,th).
     * Camera: photo · video:<s> · mode:photo|video · front · back · zoom:<r> · menu · unmenu · kit · wait:<ms>.
     */
    private fun runActs(intent: Intent?) {
        val acts = intent?.getStringExtra("act") ?: return
        intent.removeExtra("act")
        PrismLog.i("ACT $acts")
        lifecycleScope.launch {
            for (a in acts.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, v) = a.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
                if (k in setOf("photo", "video", "mode", "front", "back", "zoom", "rec", "snap", "shutter", "hold", "aspect", "smooth")) {
                    var n = 0
                    while (!cam.streaming && n++ < 100) delay(100)
                    delay(300)
                }
                when (k) {
                    "dark" -> PrismPrefs.useDark(true)
                    "light" -> PrismPrefs.useDark(false)
                    "glass" -> PrismPrefs.useLook(Look.Glass)
                    "classic" -> PrismPrefs.useLook(Look.Classic)
                    "th", "en" -> PrismPrefs.useLang(k)
                    "kit" -> showKit = true
                    "menu" -> menuOpen = true
                    "unmenu" -> menuOpen = false
                    "photo" -> { cam.selectMode(PrismCamera.Mode.Photo); delay(200); cam.takePhoto(); delay(1500) }
                    "video" -> {
                        cam.selectMode(PrismCamera.Mode.Video)
                        var n = 0; while (!cam.streaming && n++ < 100) delay(100)
                        delay(500)
                        cam.startRecording(); delay((v?.toLongOrNull() ?: 3) * 1000); cam.stopRecording(); delay(1500)
                    }
                    "mode" -> cam.selectMode(if (v == "video") PrismCamera.Mode.Video else PrismCamera.Mode.Photo)
                    "front" -> if (!cam.front) cam.switchLens()
                    "back" -> if (cam.front) cam.switchLens()
                    "zoom" -> cam.zoomTo(v?.toFloatOrNull() ?: 1f)
                    "wait" -> delay(v?.toLongOrNull() ?: 1000)
                    "zsl" -> cam.zslAllowed = v != "0"
                    "rec" -> cam.startRecording()
                    "stop" -> cam.stopRecording()
                    "pause" -> cam.togglePause()
                    "snap" -> cam.takePhoto()
                    "look" -> cam.useLook(com.prism.cam.effects.Looks.of(v))
                    "text" -> com.prism.cam.effects.Scene.addText(v ?: "Prism Cam", android.graphics.Color.WHITE, 3)
                    "sticker" -> com.prism.cam.effects.Scene.addSticker(v ?: "✨")
                    "frame" -> { com.prism.cam.effects.Scene.frame = v ?: "none"; com.prism.cam.effects.Scene.touch() }
                    "fxsheet" -> fxOpen = v != "0"
                    "bg" -> when (v) { "blur" -> vision.useBg(1); "replace" -> vision.useBgId("sunset"); else -> vision.useBg(0) }
                    "face" -> vision.useFaceSticker(v?.takeIf { it != "none" })
                    "peace" -> vision.usePeace(v == "1")
                    "portrait" -> cam.selectMode(PrismCamera.Mode.Portrait)
                    "pc" -> { cam.lendCamera(); pcOpen = true }
                    "unpc" -> pcOpen = false
                    "updcheck" -> com.prism.cam.update.Updater.check()
                    "vframe" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // test: first second of the last video → files/vframe.png (checks what was really recorded)
                        val item = com.prism.cam.camera.Gallery.latest(this@MainActivity)
                        if (item != null && item.video) android.media.MediaMetadataRetriever().run {
                            try {
                                setDataSource(this@MainActivity, item.uri)
                                val rot = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                val br = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                                PrismLog.i("VFRAME rot=$rot bitrate=$br")
                                getFrameAtTime(1_000_000)?.let { f -> java.io.File(getExternalFilesDir(null), "vframe.png").outputStream().use { o -> f.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) } }
                            } finally { release() }
                        }
                    }
                    "fxclear" -> { com.prism.cam.effects.Scene.clear(); cam.useLook(com.prism.cam.effects.Looks.all[0]); cam.clearAiLooks() }
                    "clean" -> cleanReq = (cleanReq?.let { kotlin.math.abs(it) } ?: 0) + 1
                    "unclean" -> cleanReq = -((cleanReq?.let { kotlin.math.abs(it) } ?: 0) + 1)
                    "timer" -> com.prism.cam.prefs.CamPrefs.useTimer(v?.toIntOrNull() ?: 0)
                    "grid" -> com.prism.cam.prefs.CamPrefs.useGrid(v == "1")
                    "lefty" -> com.prism.cam.prefs.CamPrefs.useLefty(v == "1")
                    "smooth" -> { com.prism.cam.prefs.CamPrefs.useSmooth(v == "1"); cam.applyShape() }
                    "aspect" -> { com.prism.cam.prefs.CamPrefs.useAspect(com.prism.cam.prefs.CamPrefs.Aspect.of(v)); cam.applyShape() }
                    "shutter" -> cam.shutter()
                    "hold" -> { cam.holdStart(); delay(v?.toLongOrNull() ?: 3000); cam.holdEnd(); delay(1500) }
                    "orient" -> testOrient = when (v) {
                        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                PrismLog.i("ACT done $a")
            }
        }
    }
}
