package com.prism.cam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.studio.Content
import com.prism.cam.studio.Layer
import com.prism.cam.studio.Make
import com.prism.cam.studio.Studio
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismFont
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * Type / edit a text sticker: big live preview of the text in its style over a dim veil, colour dots, style chips.
 * [item] null = new text. Empty text on "done" removes it.
 */
@Composable
fun TextEditor(item: Layer?, onClose: () -> Unit) {
    var text by remember { mutableStateOf(item?.text ?: "") }
    var color by remember { mutableIntStateOf(item?.color ?: android.graphics.Color.WHITE) }
    var style by remember { mutableIntStateOf(item?.style ?: 0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun done() {
        val t = text.trim()
        when {
            item == null && t.isNotEmpty() -> Make.text(t, color, style)
            item != null && t.isEmpty() -> Studio.remove(item.id)
            item != null -> { item.text = t; item.color = color; item.style = style; item.name = t.take(20); item.touchContent(); Studio.commit("text") }
        }
        onClose()
    }
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(remember { MutableInteractionSource() }, indication = null) { done() }
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) { },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val c = Color(color)
            val base = TextStyle(fontFamily = PrismFont, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, textAlign = TextAlign.Center, lineHeight = 40.sp)
            val shown = when (style) {
                3 -> base.copy(brush = Prism.gradText())
                else -> base.copy(color = c, shadow = if (style == 0) androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 3f), 10f) else null)
            }
            Box(
                Modifier.then(if (style == 2) Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xB3000000)).padding(horizontal = 18.dp, vertical = 8.dp) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    text, { text = it },
                    Modifier.focusRequester(focus).widthIn(min = 120.dp).heightIn(min = 48.dp),
                    textStyle = shown,
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (text.isEmpty()) com.prism.cam.ui.kit.PrismText(T("พิมพ์ข้อความ…"), style = base.copy(color = Color(0x80FFFFFF)))
                            inner()
                        }
                    },
                )
            }
            // colours
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (col in Content.textColors) {
                    val on = col == color
                    Box(
                        Modifier.size(if (on) 34.dp else 28.dp).clip(CircleShape).background(Color(col))
                            .border(if (on) 3.dp else 1.5.dp, if (on) Brush.linearGradient(listOf(Color(0xFF8EA2FF), Color(0xFFFF9EC7))) else SolidColor(Color(0xCCFFFFFF)), CircleShape)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { color = col },
                    )
                }
            }
            // styles
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(T("เงา"), T("ขอบ"), T("ป้าย"), "Prism").forEachIndexed { i, s -> PrismChip(s, style == i, { style = i }) }
            }
            Row(
                Modifier.prismPane(PrismThemeAccess.colors, RoundedCornerShape(50), strong = true).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrismPill(T("ยกเลิก"), onClose)
                Spacer(Modifier.size(2.dp))
                PrismPill(T("เสร็จ"), { done() }, kind = PillKind.Primary, icon = "check")
            }
        }
    }
}
