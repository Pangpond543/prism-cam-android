package com.prism.cam.studio

import android.graphics.Bitmap
import androidx.compose.runtime.snapshotFlow
import com.prism.cam.PrismLog
import com.prism.cam.ai.Vision
import com.prism.cam.camera.PrismCamera
import com.prism.cam.effects.Looks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Keeps the GPU compositor in step with the studio document:
 * - every change → immutable snapshots ([RLayer]) for the GL thread (moving / turning costs nothing else);
 * - content changes (text, colour, file, frame…) → pixels rendered off the main thread and handed to the GPU;
 * - animated GIFs → frames pushed ~15 times a second;
 * - tells the AI which trackers the layers need (face / hands) and the camera whether the GPU pipeline is needed at all
 *   (a plain full-screen camera with nothing on it runs the zero-cost path).
 */
object Pump {
    private val inFlight = ConcurrentHashMap<Long, String>()

    fun snapshot(l: Layer, cw: Int, ch: Int): RLayer = RLayer(
        id = l.id, type = l.type, x = l.x, y = l.y, w = l.w, aspect = l.aspect, rot = l.rot, opacity = l.opacity,
        flipX = l.flipX, flipY = l.flipY, crop = floatArrayOf(l.cropL, l.cropT, l.cropR, l.cropB), radius = l.radius,
        circle = l.mask == "circle", border = l.border, borderColor = l.borderColor, shadow = l.shadow, blend = l.blend,
        color = Geometry.colorOf(l), keyOn = l.keyOn, keyColor = l.keyColor, keyTol = l.keyTol,
        attachKind = l.attach.kind, attachPoint = l.attach.point, attachWho = l.attach.who, followRot = l.attach.followRot,
        followScale = l.attach.followScale, followTurn = l.attach.followTurn, ox = l.attach.ox, oy = l.attach.oy,
        attachSize = l.attach.size, lost = l.attach.lost, only = l.attach.only, anim = l.anim, animSpeed = l.animSpeed,
        contentKey = contentKey(l, cw, ch), media = l.media, loop = l.loop, muted = l.muted, speed = l.speed, lens = l.lens, look = l.look, tint = l.color, preset = l.preset, tint2 = l.color2,
        keys = if (l.keys.isEmpty()) FloatArray(0) else l.keys.sortedBy { it[0] }.flatMap { it.toList() }.toFloatArray(), keyLen = l.keyLen,
    )

    /** what the pixels depend on (moving / turning / colour adjustments do not re-render) */
    fun contentKey(l: Layer, cw: Int, ch: Int): String = when (l.type) {
        LType.Camera, LType.Video, LType.Screen, LType.Avatar, LType.FaceFx, LType.HandFx, LType.Lyrics -> ""
        else -> buildString {
            append(l.type.name).append('|').append(l.contentVersion).append('|').append(l.text).append('|').append(l.color).append('|')
            append(l.color2).append('|').append(l.style).append('|').append(l.shape).append('|').append(l.grad).append('|')
            append(l.media).append('|').append(l.preset).append('|').append(l.strokes.size)
            if (Geometry.isFullCanvas(l.type)) append('|').append(cw).append('x').append(ch)
        }
    }

    /** nothing but the camera filling the picture, untouched → no GPU pipeline needed */
    fun isPlain(list: List<RLayer>): Boolean {
        if (list.size != 1) return false
        val c = list[0]
        return c.type == LType.Camera && abs(c.x - .5f) < 1e-3f && abs(c.y - .5f) < 1e-3f && abs(c.w - 1f) < 1e-3f && c.rot == 0f &&
            c.crop.all { it == 0f } && !c.flipX && !c.flipY && c.radius == 0f && c.border == 0f && c.opacity >= 1f && !c.keyOn &&
            c.anim.isEmpty() && c.color.contentEquals(Looks.IDENTITY) && !c.circle && c.shadow == 0f
    }

    fun start(scope: CoroutineScope, cam: PrismCamera, vision: () -> Vision?) {
        val fx = cam.fx
        // 1) snapshots
        scope.launch {
            snapshotFlow {
                val cw = fx.canvasW.coerceAtLeast(1); val ch = fx.canvasH.coerceAtLeast(1)
                Studio.version // structure changes
                val l = Studio.layers.filter { it.visible }.map { snapshot(it, cw, ch) }
                // commands, pose recording and "fit to my face" need the trackers too
                val cmdFace = Gestures.calibrating || Gestures.commands.any { it.trigger in Gestures.faceTriggers || it.trigger.startsWith("zone:") }
                val cmdHand = Gestures.recording != null || Gestures.commands.any { it.trigger !in Gestures.faceTriggers }
                Triple(l, cmdFace, cmdHand)
            }.collect { (list, cmdFace, cmdHand) ->
                fx.layers = list
                val face = cmdFace || list.any { it.attachKind == "face" || it.type == LType.FacePaint || it.type == LType.Avatar || it.type == LType.FaceFx }
                val hand = cmdHand || list.any { (it.attachKind == "hand" || it.attachKind == "hands2") || it.type == LType.HandFx }
                vision()?.need(face, hand)
                cam.fxReason("scene", !isPlain(list))
                Studio.camera?.let { c -> if (c.look != cam.look.id) cam.syncLook(c.look) }
                render(scope, fx, list)
            }
        }
        // 1a) scene slideshow
        scope.launch {
            var since = android.os.SystemClock.uptimeMillis()
            while (true) {
                delay(250)
                val sec = Studio.autoPlay
                if (sec <= 0 || Studio.scenes.size < 2) { since = android.os.SystemClock.uptimeMillis(); continue }
                if (android.os.SystemClock.uptimeMillis() - since >= sec * 1000L) { since = android.os.SystemClock.uptimeMillis(); Studio.goScene((Studio.current + 1) % Studio.scenes.size) }
            }
        }
        // 1b) VTuber characters: redrawn ~30 times a second from the face they copy
        scope.launch {
            val bufs = HashMap<Long, Array<Bitmap?>>()
            var flip = 0
            while (true) {
                delay(33)
                Gestures.front = cam.front
                val avs = fx.layers.filter { it.type == LType.Avatar }
                if (avs.isEmpty()) { if (bufs.isNotEmpty()) { bufs.keys.forEach { FaceArt.dropRig(it) }; bufs.clear() }; continue }
                flip = 1 - flip
                withContext(Dispatchers.Default) {
                    for (a in avs) {
                        val arr = bufs.getOrPut(a.id) { arrayOfNulls(2) }
                        val b = arr[flip]?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { arr[flip] = it }
                        val faces = Tracking.faces
                        val f = faces.getOrNull(a.attachWho.toIntOrNull() ?: 0)
                        val rig = FaceArt.rig(a.id, f, cam.front)
                        FaceArt.drawAvatar(b, a.preset.ifEmpty { "cat" }, a.tint, a.tint2, rig)
                        fx.pending[a.id] = b to "av"
                    }
                }
                bufs.keys.retainAll(avs.mapTo(HashSet()) { it.id })
            }
        }
        // 2) canvas size changes (frames / drawings are drawn at canvas size) + GIF frames
        scope.launch {
            var lastW = 0; var lastH = 0
            val gifBufs = HashMap<Long, Array<Bitmap?>>()
            var flip = 0
            while (true) {
                delay(70)
                if (fx.canvasW != lastW || fx.canvasH != lastH) {
                    lastW = fx.canvasW; lastH = fx.canvasH
                    if (lastW > 0) Make.canvasAspect = lastH.toFloat() / lastW
                    Studio.touch()
                }
                val gifs = fx.layers.filter { it.type == LType.Image && it.media.endsWith(".gif", true) }
                if (gifs.isEmpty()) { if (gifBufs.isNotEmpty()) gifBufs.clear(); continue }
                flip = 1 - flip
                withContext(Dispatchers.Default) {
                    for (g in gifs) {
                        val bufs = gifBufs.getOrPut(g.id) { arrayOfNulls(2) }
                        val f = File(Content.mediaDir, g.media)
                        val b = Content.gifFrame(f, bufs[flip], 720) ?: continue
                        bufs[flip] = b
                        fx.pending[g.id] = b to g.contentKey
                    }
                }
                gifBufs.keys.retainAll(gifs.mapTo(HashSet()) { it.id })
            }
        }
    }

    private fun render(scope: CoroutineScope, fx: com.prism.cam.effects.GlFx, list: List<RLayer>) {
        val cw = fx.canvasW.coerceAtLeast(16); val ch = fx.canvasH.coerceAtLeast(16)
        if (fx.canvasW == 0) return // the GPU has not started yet; the size change will trigger us again
        for (r in list) {
            if (r.contentKey.isEmpty()) continue
            if (fx.uploaded[r.id] == r.contentKey || fx.pending[r.id]?.second == r.contentKey) continue
            // one render per layer at a time; a newer change re-triggers when it finishes (drawing / typing stay smooth)
            if (inFlight.containsKey(r.id)) continue
            val l = Studio.layers.firstOrNull { it.id == r.id } ?: continue
            inFlight[r.id] = r.contentKey
            scope.launch {
                val full = Geometry.isFullCanvas(l.type)
                val res = withContext(Dispatchers.Default) { Content.render(l, cw, ch, if (full) 1f else 2f) }
                inFlight.remove(r.id)
                if (res == null) { PrismLog.i("STUDIO render null ${l.type}"); return@launch }
                val (bmp, aspect) = res
                fx.pending[r.id] = bmp to r.contentKey
                if (!full && abs(l.aspect - aspect) > 1e-3f) l.aspect = aspect
                if (contentKey(l, cw, ch) != r.contentKey) Studio.touch()
            }
        }
    }
}
