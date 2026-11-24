package com.prism.cam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismBackground
import com.prism.cam.ui.kit.PrismLogo
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.PrismWordmark
import com.prism.cam.ui.theme.PrismThemeAccess

/** First start / camera not allowed: says why, one button. If Android stopped asking, the button opens Settings. */
@Composable
fun PermissionScreen(blocked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    val c = PrismThemeAccess.colors
    Box(Modifier.fillMaxSize()) {
        PrismBackground()
        Column(
            Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp).widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrismLogo(84.dp)
            Spacer(Modifier.height(10.dp))
            PrismWordmark(size = 26f)
            PrismText(
                if (blocked) T("ปิดสิทธิ์กล้องไว้อยู่ เปิดได้ในการตั้งค่า → สิทธิ์ → กล้อง")
                else T("Prism Cam ต้องใช้กล้องเพื่อถ่ายรูปและวิดีโอ ภาพทั้งหมดเก็บในเครื่องคุณเท่านั้น"),
                style = PrismThemeAccess.type.body, color = c.ink2, align = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            if (blocked) PrismPill(T("เปิดการตั้งค่า"), onSettings, kind = PillKind.Primary, icon = "sliders")
            else PrismPill(T("อนุญาตใช้กล้อง"), onAllow, kind = PillKind.Primary, icon = "camera")
        }
    }
}
