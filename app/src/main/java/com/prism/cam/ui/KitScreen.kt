package com.prism.cam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.BuildConfig
import com.prism.cam.i18n.T
import com.prism.cam.prefs.PrismPrefs
import com.prism.cam.ui.kit.CamRoundButton
import com.prism.cam.ui.kit.CamShutter
import com.prism.cam.ui.kit.CamZoomPills
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismBackground
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismLogo
import com.prism.cam.ui.kit.PrismPanel
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismSegmented
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.PrismToast
import com.prism.cam.ui.kit.PrismWordmark
import com.prism.cam.ui.theme.Look
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess
import kotlinx.coroutines.delay

/**
 * Phase 0 screen: shows every Prism component on the real phone (both looks × both themes × TH/EN),
 * so the kit can be checked before the camera is built on top of it in phase 1.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KitScreen() {
    val c = PrismThemeAccess.colors
    val type = PrismThemeAccess.type
    var toast by remember { mutableStateOf<String?>(null) }
    var chip by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(1) }
    var rec by remember { mutableStateOf(false) }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

    Box(Modifier.fillMaxSize()) {
        PrismBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ── hero
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PrismLogo(84.dp)
                    Spacer(Modifier.height(18.dp))
                    PrismWordmark(size = 26f)
                    PrismText("v${BuildConfig.VERSION_NAME}", style = type.caption, color = c.ink3)
                    Spacer(Modifier.height(4.dp))
                    PrismText(T("เฟส 0 · เครื่องมือพร้อม ติดตั้งลงมือถือได้แล้ว"), style = type.caption, color = c.ink2, align = TextAlign.Center)
                }

                // ── appearance (same rows as the desktop moon-button menu)
                PrismPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Section(T("หน้าตาโปรแกรม"), "sun")
                        Label(T("ธีม"))
                        PrismSegmented(
                            listOf(T("สว่าง") to "day", T("มืด") to "moon"),
                            if (PrismPrefs.dark) 1 else 0, { PrismPrefs.useDark(it == 1) }, Modifier.fillMaxWidth(),
                        )
                        Label(T("สไตล์"))
                        PrismSegmented(
                            listOf(T("คลาสสิก") to "plain", T("แก้ว") to "sparkle"),
                            if (PrismPrefs.look == Look.Glass) 1 else 0, { PrismPrefs.useLook(if (it == 1) Look.Glass else Look.Classic) }, Modifier.fillMaxWidth(),
                        )
                        Label(T("ภาษา / Language"))
                        PrismSegmented(
                            listOf("ไทย" to null, "English" to null),
                            if (PrismPrefs.lang == "en") 1 else 0, { PrismPrefs.useLang(if (it == 1) "en" else "th") }, Modifier.fillMaxWidth(),
                        )
                        PrismText(T("คลาสสิก = เรียบ นิ่ง เบาเครื่อง · แก้ว = กระจกฝ้าไล่สีแบบ Prism"), style = type.caption, color = c.ink3)
                    }
                }

                // ── camera controls on a stand-in picture
                PrismPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Section(T("ปุ่มบนภาพกล้อง"), "camera")
                        FakePicture(
                            zoom = zoom, onZoom = { zoom = it }, recording = rec,
                            onShutter = { if (rec) rec = false else toast = T("บันทึกรูปแล้ว") },
                            onLongRec = { rec = !rec },
                        )
                    }
                }

                // ── buttons + chips
                PrismPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Section(T("ปุ่ม"), "plus")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrismPill(T("ถ่ายรูป"), { toast = T("บันทึกรูปแล้ว") }, kind = PillKind.Primary, icon = "shutter")
                            PrismPill(T("ยกเลิก"), {}, icon = "x")
                            PrismPill(T("ลบ"), { toast = T("ลบ") }, kind = PillKind.Warn, icon = "trash")
                        }
                        Section(T("ชิป"), "sparkle")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(T("ฟิลเตอร์") to "wand", T("สติกเกอร์") to "sticker", T("ข้อความ") to "text", T("กรอบ") to "frame")
                                .forEachIndexed { i, (s, ic) -> PrismChip(s, chip == i, { chip = i }, icon = ic) }
                        }
                    }
                }

                // ── glass pane + About content (Info box of the suite)
                PrismPanel(Modifier.fillMaxWidth(), strong = true, radius = 28.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismLogo(64.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrismText("Prism Cam", style = type.title)
                            PrismText("  v${BuildConfig.VERSION_NAME}", style = type.caption, color = c.ink3)
                        }
                        PrismText(
                            "Prism Cam is a minimal, premium camera for phones and tablets — photos and video with Prism effects.",
                            style = type.caption, color = c.ink2, align = TextAlign.Center,
                        )
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (c.isGlass) c.panel else c.fill).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Kv(T("เวอร์ชัน"), "${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}")
                            Kv(T("ผู้สร้าง"), Prism.AUTHOR)
                            Kv(T("สร้างด้วยโมเดล AI"), Prism.MODEL)
                            Kv("GitHub", T("ยังไม่ได้ตั้งลิงก์"), muted = true)
                        }
                        PrismText(T("แผ่นนี้ใช้ทำเมนู แผงฟิลเตอร์ และหน้าต่างเกี่ยวกับ"), style = type.caption, color = c.ink3, align = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        AnimatedVisibility(
            toast != null,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 22.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) { PrismToast(toast ?: "") }
    }
}

@Composable
private fun Section(text: String, icon: String) {
    val c = PrismThemeAccess.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrismIcon(icon, size = 16.dp, tint = c.ink2)
        Spacer(Modifier.size(8.dp))
        PrismText(text, style = PrismThemeAccess.type.label.copy(fontSize = 13.sp), color = c.ink2)
    }
}

@Composable
private fun Label(text: String) {
    PrismText(text, style = PrismThemeAccess.type.caption.copy(fontWeight = FontWeight.Medium), color = PrismThemeAccess.colors.ink3)
}

@Composable
private fun Kv(k: String, v: String, muted: Boolean = false) {
    val c = PrismThemeAccess.colors
    Row(Modifier.fillMaxWidth()) {
        PrismText(k, Modifier.weight(1f), style = PrismThemeAccess.type.caption, color = c.ink3)
        PrismText(v, style = PrismThemeAccess.type.caption.copy(fontWeight = FontWeight.Medium), color = if (muted) c.ink3 else c.ink)
    }
}

/** A stand-in for the live picture (phase 1 puts the real camera here) with the minimal controls on top. */
@Composable
private fun FakePicture(zoom: Int, onZoom: (Int) -> Unit, recording: Boolean, onShutter: () -> Unit, onLongRec: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF4A5058), Color(0xFF3A3F45), Color(0xFF262A2F))))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(22.dp)),
    ) {
        // "person"
        Box(
            Modifier.align(Alignment.Center).padding(bottom = 40.dp).size(110.dp, 140.dp)
                .clip(RoundedCornerShape(topStart = 55.dp, topEnd = 55.dp, bottomStart = 30.dp, bottomEnd = 30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6A7078), Color(0xFF545A62)), start = Offset.Zero, end = Offset.Infinite)),
        )
        // top row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismIcon("boltoff", size = 22.dp, tint = Color.White, stroke = 1.6f)
            PrismIcon("clock", size = 22.dp, tint = Color.White, stroke = 1.6f)
            Box(Modifier.border(1.3.dp, Color(0xCCFFFFFF), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp)) {
                PrismText("3:4", style = PrismThemeAccess.type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            PrismIcon("dots", size = 22.dp, tint = Color.White, stroke = 1.6f)
        }
        // bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CamZoomPills(listOf(.5f, 1f, 2f), zoom, onZoom)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(T("วิดีโอ"), T("รูป"), T("ภาพบุคคล")).forEachIndexed { i, s ->
                    PrismText(s, style = PrismThemeAccess.type.caption.copy(fontSize = 12.sp, fontWeight = if (i == 1) FontWeight.SemiBold else FontWeight.Normal), color = if (i == 1) Color.White else Color(0x8CFFFFFF))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF6F757C)).border(1.5.dp, Color(0xCCFFFFFF), CircleShape))
                CamShutter(onShutter, recording = recording)
                CamRoundButton("camswitch", onLongRec)
            }
        }
    }
}
