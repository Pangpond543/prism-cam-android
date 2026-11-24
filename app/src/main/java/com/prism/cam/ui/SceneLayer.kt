package com.prism.cam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.cam.camera.PrismCamera
import com.prism.cam.effects.Scene
import com.prism.cam.ui.kit.PrismIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Keeps the GPU overlay texture in step with the scene: whenever text / stickers / frame change (or the picture
 * shape changes) the scene is drawn into a transparent bitmap off the main thread and handed to the GPU pipeline.
 * Two bitmaps alternate so the one being uploaded is never drawn into.
 */
@Composable
fun SceneLink(cam: PrismCamera, aspect: Float) {
    LaunchedEffect(cam) {
        snapshotFlow { Scene.isEmpty }.collectLatest { empty -> cam.fxReason("scene", !empty) }
    }
    val pool = remember { arrayOfNulls<Bitmap>(2) }
    LaunchedEffect(aspect) {
        var flip = 0
        snapshotFlow { Scene.version to Scene.isEmpty }.collectLatest { (_, empty) ->
            if (empty) { cam.fx.setOverlay(null); return@collectLatest }
            // ≤ 1280 px on the long side: sharp on a 1080p video, cheap to redraw while dragging
            val w = if (aspect >= 1f) 1280 else (1280 * aspect).toInt()
            val h = if (aspect >= 1f) (1280 / aspect).toInt() else 1280
            while (cam.fx.overlayBusy()) delay(8)
            flip = 1 - flip
            val b = withContext(Dispatchers.Default) { Scene.renderBitmap(w, h, pool[flip]) }
            pool[flip] = b
            cam.fx.setOverlay(b)
        }
    }
}

/** Dashed box + delete button around the selected item (inside the picture box). */
@Composable
fun SelectionBox(boxW: Dp, boxH: Dp) {
    val id = Scene.selected ?: return
    val item = Scene.items.firstOrNull { it.id == id } ?: return
    @Suppress("UNUSED_EXPRESSION") Scene.version // redraw while dragging
    val w = boxW * (item.halfW * 2f) + 12.dp
    val h = boxH * (item.halfH * 2f) + 12.dp
    val cx = boxW * item.x
    val cy = boxH * item.y
    Box(
        Modifier.offset(cx - w / 2, cy - h / 2).size(w, h).graphicsLayer { rotationZ = item.rot }
            .drawBehind {
                drawRoundRect(
                    Color(0xE6FFFFFF), cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            },
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(12.dp, (-12).dp).size(28.dp).clip(CircleShape)
                .background(Color(0xE6FF6B8B)).border(1.dp, Color.White, CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { Scene.remove(id) },
            contentAlignment = Alignment.Center,
        ) { PrismIcon("x", size = 15.dp, tint = Color.White, stroke = 2.2f) }
    }
}

