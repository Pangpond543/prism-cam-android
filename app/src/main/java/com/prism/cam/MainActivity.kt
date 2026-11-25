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
    /** text editor: null = closed, a text Layer or NEW_TEXT */
    private var textEdit by mutableStateOf<Any?>(null)
    private var showKit by mutableStateOf(false)
    private var layersOpen by mutableStateOf(false)
    private var propsLayer by mutableStateOf<com.prism.cam.studio.Layer?>(null)
    private var trackOpen by mutableStateOf(false)
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
        com.prism.cam.studio.Content.init(this)
        cam.fx.lite = com.prism.cam.prefs.CamPrefs.lite
        com.prism.cam.studio.FaceArt.init(this)
        com.prism.cam.studio.Gestures.init(this)
        com.prism.cam.studio.Gestures.run = { a -> gestureAction(a) }
        vision.onTracked = { com.prism.cam.studio.Gestures.onFrame() }
        com.prism.cam.studio.Studio.init(this)
        com.prism.cam.studio.Pump.start(lifecycleScope, cam) { vision }
        // photos with effects: the GPU draws the whole scene again on the full-resolution still
        cam.photoFx = {
            com.prism.cam.effects.PhotoFx.Job(cam.displaySensorRotation(), { vision.photoMask(it) }, cam.look.vignette)
        }
        // the screen's rotation must be known before the first bind: a saved scene turns effects on right away, and
        // the effect geometry is built from it (seen: scene turned 90° on a landscape tablet at cold start)
        cam.setDisplayRotation(try { if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation } catch (_: Throwable) { 0 })
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
                        else -> CameraScreen(cam, onMenu = { menuOpen = true }, onFx = { fxOpen = true }, onEditText = { textEdit = it },
                            onLayers = { layersOpen = true }, onLayerProps = { propsLayer = it }, onVideoMode = {
                            if (!has(Manifest.permission.RECORD_AUDIO)) askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }, freeOrientation = testOrient, cleanRequest = cleanReq)
                    }
                    if (camGranted && !showKit) FxSheet(fxOpen, cam, onClose = { fxOpen = false }, onNewText = { fxOpen = false; textEdit = NEW_TEXT }, extraTabs = aiTabs())
                    if (camGranted && !showKit) {
                        com.prism.cam.ui.LayersSheet(layersOpen, onClose = { layersOpen = false }, onProps = { layersOpen = false; propsLayer = it },
                            onAddMedia = { pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            onNewText = { layersOpen = false; textEdit = NEW_TEXT },
                            onShare = { if (!com.prism.cam.studio.Project.share(this@MainActivity)) say(T("ส่งไม่สำเร็จ"), false) },
                            onOpenProject = { openProject.launch(arrayOf("*/*")) })
                        com.prism.cam.ui.PropsSheet(propsLayer, onClose = { propsLayer = null }, onEditText = { propsLayer = null; textEdit = it }, vision = vision)
                    }
                    textEdit?.let { t -> TextEditor(t as? com.prism.cam.studio.Layer) { textEdit = null } }
                    MenuSheet(menuOpen, onClose = { menuOpen = false }, onCameraChange = { cam.applyShape() }, onSendToPc = { menuOpen = false; cam.lendCamera(); pcOpen = true }, speed = { com.prism.cam.ui.SpeedPanel(cam, vision) })
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

    /** a face / hand command fired (Tracking tab) */
    private fun gestureAction(a: String) {
        val st = com.prism.cam.studio.Studio
        when (a) {
            "photo" -> { if (cam.mode == PrismCamera.Mode.Video) cam.selectMode(PrismCamera.Mode.Photo); cam.shutter() }
            "video" -> if (cam.recording) cam.stopRecording() else { if (cam.mode != PrismCamera.Mode.Video) cam.selectMode(PrismCamera.Mode.Video); lifecycleScope.launch { var n = 0; while (!cam.streaming && n++ < 60) delay(50); cam.startRecording() } }
            "nextScene" -> st.goScene((st.current + 1) % st.scenes.size)
            "prevScene" -> st.goScene((st.current - 1 + st.scenes.size) % st.scenes.size)
            "nextLook" -> { val all = com.prism.cam.effects.Looks.all; cam.useLook(all[(all.indexOf(cam.look) + 1) % all.size]); say(T(cam.look.th)) }
            "clearDraw" -> com.prism.cam.studio.Gestures.clearDrawing()
            "toggleTop" -> st.layers.lastOrNull { it.type != com.prism.cam.studio.LType.Camera }?.let { it.visible = !it.visible; st.commit("toggle") }
        }
    }

    /** open a .prismcam project: its scenes are added */
    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.prism.cam.studio.Project.open(this@MainActivity, uri) }
            if (n > 0) say(T("เพิ่มฉากแล้ว") + " $n") else say(T("เปิดไฟล์นี้ไม่ได้"), false)
        }
    }

    /** add pictures / GIFs / videos as layers (several at once), copied into the app's library */
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { com.prism.cam.studio.Content.import(it) } }
            if (list.isEmpty()) { say(T("เปิดไฟล์นี้ไม่ได้"), false); return@launch }
            list.forEachIndexed { i, m -> com.prism.cam.studio.Make.media(m).apply { x = (.5f + i * .05f).coerceAtMost(.9f); y = (.5f + i * .05f).coerceAtMost(.9f) } }
            layersOpen = false
            say(T("เพิ่มแล้ว") + " ${list.size}")
        }
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
            camGranted && !menuOpen && !showKit && textEdit == null && !pcOpen && !layersOpen && propsLayer == null
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
        // acts that change the user's scenes: keep a copy first ("studiorestore" puts it back)
        val changing = setOf("studioreset", "template", "two", "face", "facepaint", "avatar", "facefx", "handfx", "text", "sticker", "frame",
            "shape", "layout", "scene", "addmedia", "attach", "anim", "set", "w", "radius", "sat", "circle", "keydemo", "look", "undo", "redo")
        if (acts.split(',').any { it.trim().substringBefore(':') in changing }) com.prism.cam.studio.Studio.keepForTest()
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
                    "text" -> com.prism.cam.studio.Make.text(v ?: "Prism Cam", android.graphics.Color.WHITE, 3)
                    "sticker" -> com.prism.cam.studio.Make.sticker(v ?: "✨")
                    "frame" -> com.prism.cam.studio.Make.frame(v ?: "none")
                    "shape" -> com.prism.cam.studio.Make.shape(v ?: "star")
                    "layers" -> layersOpen = v != "0"
                    "props" -> propsLayer = if (v == "0") null else com.prism.cam.studio.Studio.selected() ?: com.prism.cam.studio.Studio.layers.lastOrNull()
                    "sel" -> com.prism.cam.studio.Studio.layers.getOrNull(v?.toIntOrNull() ?: -1).let { com.prism.cam.studio.Studio.select(it?.id) }
                    "undo" -> com.prism.cam.studio.Studio.undo()
                    "redo" -> com.prism.cam.studio.Studio.redo()
                    "layout" -> com.prism.cam.studio.Make.cameraLayout(v ?: "fill")
                    "scene" -> if (v == "add") com.prism.cam.studio.Studio.addScene() else com.prism.cam.studio.Studio.goScene(v?.toIntOrNull() ?: 0)
                    "studiorestore" -> com.prism.cam.studio.Studio.restoreAfterTest()
                    "studioreset" -> { com.prism.cam.studio.Studio.reset(); com.prism.cam.studio.Studio.commit("reset") }
                    "addmedia" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // test: add a file from the app's own folder (/sdcard/Android/data/com.prism.cam/files/<name>) as a layer
                        val f = java.io.File(getExternalFilesDir(null), v ?: "test.png")
                        com.prism.cam.studio.Content.import(android.net.Uri.fromFile(f))
                    }?.let { com.prism.cam.studio.Make.media(it) }
                    "attach" -> com.prism.cam.studio.Studio.selected()?.let { l ->
                        // attach:<face|hand>.<point>
                        val (kind, point) = (v ?: "face.eyes").split('.').let { it[0] to it.getOrElse(1) { "eyes" } }
                        l.attach.kind = kind; l.attach.point = point; l.attach.who = if (kind == "face") "0" else "any"; com.prism.cam.studio.Studio.commit("attach")
                    }
                    "anim" -> com.prism.cam.studio.Studio.selected()?.let { it.anim = v ?: ""; com.prism.cam.studio.Studio.commit("anim") }
                    "set" -> com.prism.cam.studio.Studio.selected()?.let { l ->
                        // set:<prop>=<number> on the selected layer (w, x, y, rot, opacity, radius, border, shadow, bright, sat, crop)
                        val (pk, pv) = (v ?: "").split('=').let { it[0] to (it.getOrNull(1)?.toFloatOrNull() ?: 0f) }
                        when (pk) {
                            "w" -> l.w = pv; "x" -> l.x = pv; "y" -> l.y = pv; "rot" -> l.rot = pv; "opacity" -> l.opacity = pv
                            "radius" -> l.radius = pv; "border" -> l.border = pv; "shadow" -> l.shadow = pv; "bright" -> l.bright = pv
                            "sat" -> l.sat = pv; "hue" -> l.hue = pv; "key" -> { l.keyOn = pv > 0f; l.keyTol = pv }
                            "circle" -> l.mask = if (pv > 0f) "circle" else "rect"
                        }
                        com.prism.cam.studio.Studio.commit("set")
                    }
                    "track" -> when (v) { "face0" -> vision.useFace(false); "face1" -> vision.useFace(true); "hands0" -> vision.useHands(false); "hands1" -> vision.useHands(true); "mesh1" -> vision.useMesh(true); "mesh0" -> vision.useMesh(false); else -> trackOpen = v != "0" }
                    "facepaint" -> com.prism.cam.studio.Make.facePaint(v?.takeIf { it != "none" })
                    "avatar" -> if (v == "none") { com.prism.cam.studio.Studio.layers.removeAll { it.type == com.prism.cam.studio.LType.Avatar }; com.prism.cam.studio.Studio.commit("av") } else com.prism.cam.studio.Make.avatar(v ?: "cat")
                    "facefx" -> com.prism.cam.studio.Make.faceFx(v?.takeIf { it != "none" })
                    "handfx" -> com.prism.cam.studio.Make.handFx(v?.takeIf { it != "none" })
                    "cmd" -> (v ?: "smile=photo").split('=').let { com.prism.cam.studio.Gestures.addCommand(it[0], it.getOrElse(1) { "photo" }) }
                    "cmdclear" -> { com.prism.cam.studio.Gestures.commands.clear(); com.prism.cam.studio.Gestures.saveCommands() }
                    "tracklog" -> PrismLog.i("TRACK faces=${com.prism.cam.studio.Tracking.faces.size} hands=${com.prism.cam.studio.Tracking.hands.map { it.gesture + (if (it.right) "R" else "L") }} seen='${com.prism.cam.studio.Gestures.seenNow}' ai=${vision.aiMs}ms faceGpu=${vision.faceGpu} handGpu=${vision.handGpu} drawn=${cam.fx.drawn.size}")
                    "fakehands" -> {
                        // fakehands:<n>/<spread>/<tilt>/<gesture>/<seconds>  e.g. fakehands:2/.5/15/open/8 ("|" breaks adb shell)
                        val f = (v ?: "").split('/')
                        com.prism.cam.studio.Tracking.fakeHands(f.getOrNull(0)?.toIntOrNull() ?: 2, f.getOrNull(1)?.toFloatOrNull() ?: .5f,
                            f.getOrNull(2)?.toFloatOrNull() ?: 0f, f.getOrNull(3) ?: "open", ((f.getOrNull(4)?.toFloatOrNull() ?: 8f) * 1000).toLong())
                    }
                    "fakeface" -> v?.split('/').let { f -> com.prism.cam.studio.Tracking.fakeFace(f?.getOrNull(0)?.toFloatOrNull() ?: 0f, ((f?.getOrNull(1)?.toFloatOrNull() ?: 8f) * 1000).toLong()) }
                    "two" -> com.prism.cam.studio.Make.twoHand(v ?: "banner")
                    "aidump" -> vision.dumpFrame(java.io.File(getExternalFilesDir(null), "ai.png"))
                    "facepts" -> com.prism.cam.studio.Tracking.faces.firstOrNull()?.let { f -> val p = f.pts; PrismLog.i("FACEPTS 33=%.2f,%.2f 263=%.2f,%.2f 10=%.2f,%.2f 152=%.2f,%.2f front=${cam.fx.front} drawn=${cam.fx.drawn.values.map { "%.0f".format(it.rot) }}".format(p[66], p[67], p[526], p[527], p[20], p[21], p[304], p[305])) } ?: PrismLog.i("FACEPTS none")
                    "template" -> com.prism.cam.studio.Make.template(v ?: "vlog")
                    "autoplay" -> com.prism.cam.studio.Studio.autoPlay = v?.toIntOrNull() ?: 0
                    "keydemo" -> com.prism.cam.studio.Studio.selected()?.let { l -> l.keys.clear(); l.keys += floatArrayOf(0f, .2f, .3f, .2f, -10f, 1f); l.keys += floatArrayOf(2f, .8f, .7f, .3f, 10f, 1f); l.keyLen = 4f; com.prism.cam.studio.Studio.commit("keys") }
                    "share" -> com.prism.cam.studio.Project.share(this@MainActivity)
                    "studiolog" -> PrismLog.i("STUDIO front=${cam.fx.front} layers=${com.prism.cam.studio.Studio.layers.map { it.type.name + ":" + it.attach.kind + "@" + "%.2f,%.2f w%.2f".format(it.x, it.y, it.w) + " drawn=" + cam.fx.drawn[it.id]?.let { q -> "%.0f,%.0f".format(q.cx, q.cy) } }} fx=${cam.fx.canvasW}x${cam.fx.canvasH} fps=${"%.1f".format(cam.fx.statFps)} draw=${"%.1f".format(cam.fx.statDrawMs)}ms faces=${com.prism.cam.studio.Tracking.faces.size} hands=${com.prism.cam.studio.Tracking.hands.size} gpu=${cam.fx.gpuName}")
                    "fxsheet" -> fxOpen = v != "0"
                    "fxtab" -> com.prism.cam.ui.TestTabs.fx = v?.toIntOrNull() ?: 0
                    "propstab" -> com.prism.cam.ui.TestTabs.props = v?.toIntOrNull() ?: 0
                    "bg" -> when (v) { "blur" -> vision.useBg(1); "replace" -> vision.useBgId("sunset"); else -> vision.useBg(0) }
                    "face" -> com.prism.cam.studio.Make.facePack(v?.takeIf { it != "none" })
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
                    "fxclear" -> { com.prism.cam.studio.Make.clearAll(); cam.useLook(com.prism.cam.effects.Looks.all[0]); cam.clearAiLooks() }
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
