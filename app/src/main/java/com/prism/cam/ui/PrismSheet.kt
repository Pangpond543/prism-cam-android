package com.prism.cam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.prism.cam.i18n.T
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * The side / bottom sheet every studio panel uses (same as the ✦ sheet): slides up in portrait, in from the right in
 * landscape, leaves most of the picture visible so changes are seen live. Tap outside / Back closes.
 * [tall] = up to 62 % of the height in portrait (panels with long lists).
 */
@Composable
fun PrismSheet(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    tall: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(open) { onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val land = maxWidth > maxHeight
        // portrait: the grip folds the sheet down to its header so the picture (and the layer being edited) shows;
        // touches then reach the picture (drag the layer) — the sheet stays open until ✕ / Back
        var folded by remember { mutableStateOf(false) }
        LaunchedEffect(open) { if (!open) folded = false }
        androidx.compose.runtime.LaunchedEffect(TestTabs.fold) { if (TestTabs.fold >= 0) folded = TestTabs.fold == 1 }
        AnimatedVisibility(open && !(folded && !land), enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onClose() })
        }
        AnimatedVisibility(
            open,
            Modifier.align(if (land) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (land) slideInHorizontally(tween(300, easing = Prism.ease)) { it } + fadeIn() else slideInVertically(tween(300, easing = Prism.ease)) { it } + fadeIn(),
            exit = if (land) slideOutHorizontally(tween(200)) { it } + fadeOut() else slideOutVertically(tween(200)) { it } + fadeOut(),
        ) {
            val c = PrismThemeAccess.colors
            val shape = if (land) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(if (land) Modifier.padding(12.dp).width(minOf(440.dp, maxWidth * .48f)).fillMaxHeight() else Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxHeight * (if (tall) .62f else .5f)))
                    .prismPane(c, shape, strong = true, elevation = 22.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .padding(start = 18.dp, end = 18.dp, top = if (land) 14.dp else 0.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!land) SheetGrip(folded) { folded = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismText(title, Modifier.weight(1f), style = PrismThemeAccess.type.title)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(c.hover)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { PrismIcon("x", size = 16.dp, tint = c.ink2) }
                }
                header()
                if (!(folded && !land)) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
    }
}

/**
 * Handle on top of a portrait sheet: tap or drag down = fold the sheet to its header (see the picture), tap or drag
 * up = open it again. Phones have little room under the picture, so a sheet covered the layer being edited.
 */
@Composable
fun SheetGrip(folded: Boolean, onFold: (Boolean) -> Unit) {
    val c = PrismThemeAccess.colors
    val haptic = com.prism.cam.ui.kit.rememberHaptic()
    val fold by androidx.compose.runtime.rememberUpdatedState(folded)
    val set by androidx.compose.runtime.rememberUpdatedState(onFold)
    Box(
        Modifier.fillMaxWidth().height(22.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); onFold(!folded) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = {
                        val want = if (dy > 16.dp.toPx()) true else if (dy < -16.dp.toPx()) false else fold
                        if (want != fold) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); set(want) }
                    },
                ) { _, d -> dy += d }
            }
            .semantics { contentDescription = if (folded) T("ขยายแผง") else T("พับแผงลง") },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(top = 4.dp).width(38.dp).height(4.dp).clip(RoundedCornerShape(50)).background(c.ink3.copy(alpha = .45f)))
    }
}
