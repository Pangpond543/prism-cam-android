package com.prism.cam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.cam.BuildConfig
import com.prism.cam.i18n.T
import com.prism.cam.prefs.PrismPrefs
import com.prism.cam.prefs.CamPrefs
import com.prism.cam.ui.kit.PrismSwitchRow
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismLogo
import com.prism.cam.ui.kit.PrismSegmented
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.PrismWordmark
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.theme.Look
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * The "…" menu — the suite shell on mobile (no title bar): appearance (Light/Dark · Classic/Glass, same as the
 * desktop moon-button menu), language, and the Info box. Slides up from the bottom in portrait, in from the right
 * in landscape. Picks apply at once; Back / tap outside closes.
 */
@Composable
fun MenuSheet(open: Boolean, onClose: () -> Unit, onCameraChange: () -> Unit = {}, onSendToPc: () -> Unit = {}) {
    BackHandler(open) { onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val land = maxWidth > maxHeight
        AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(remember { MutableInteractionSource() }, indication = null) { onClose() })
        }
        AnimatedVisibility(
            open,
            Modifier.align(if (land) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (land) slideInHorizontally(tween(320, easing = Prism.ease)) { it } + fadeIn() else slideInVertically(tween(320, easing = Prism.ease)) { it } + fadeIn(),
            exit = if (land) slideOutHorizontally(tween(220)) { it } + fadeOut() else slideOutVertically(tween(220)) { it } + fadeOut(),
        ) {
            val c = PrismThemeAccess.colors
            val shape = if (land) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(if (land) Modifier.padding(12.dp).width(400.dp).fillMaxHeight() else Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight * .86f))
                    .prismPane(c, shape, strong = true, elevation = 24.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // header: logo + name + close
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismLogo(40.dp)
                    Spacer(Modifier.width(12.dp))
                    PrismWordmark(Modifier.weight(1f), size = 19f)
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(c.hover)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { PrismIcon("x", size = 18.dp, tint = c.ink2) }
                }

                Label(T("ธีม"))
                PrismSegmented(listOf(T("สว่าง") to "day", T("มืด") to "moon"), if (PrismPrefs.dark) 1 else 0, { PrismPrefs.useDark(it == 1) }, Modifier.fillMaxWidth())
                Label(T("สไตล์"))
                PrismSegmented(
                    listOf(T("คลาสสิก") to "plain", T("แก้ว") to "sparkle"), if (PrismPrefs.look == Look.Glass) 1 else 0,
                    { PrismPrefs.useLook(if (it == 1) Look.Glass else Look.Classic) }, Modifier.fillMaxWidth(),
                )
                Label(T("ภาษา / Language"))
                PrismSegmented(listOf("ไทย" to null, "English" to null), if (PrismPrefs.lang == "en") 1 else 0, { PrismPrefs.useLang(if (it == 1) "en" else "th") }, Modifier.fillMaxWidth())
                PrismText(T("คลาสสิก = เรียบ นิ่ง เบาเครื่อง · แก้ว = กระจกฝ้าไล่สีแบบ Prism"), style = PrismThemeAccess.type.caption, color = c.ink3)

                // phase 6: be the camera of Prism Cam on the PC (Discord on the PC sees a real camera)
                com.prism.cam.ui.kit.PrismPill(T("ส่งภาพเข้า Prism Cam บนคอม"), onSendToPc, kind = com.prism.cam.ui.kit.PillKind.Primary, icon = "broadcast")
                PrismText(T("ใช้มือถือ/แท็บเล็ตนี้เป็นกล้องของ Prism Cam บนคอม แล้ว Discord บนคอมจะเห็นเป็นกล้องจริง"), style = PrismThemeAccess.type.caption, color = c.ink3)
                Spacer(Modifier.size(2.dp))
                // camera choices (remembered)
                Label(T("กล้อง"))
                PrismSwitchRow(T("เส้นตาราง + ระดับน้ำ"), T("เส้นแบ่ง 3 ส่วน และเส้นกลางที่เป็นสีเหลืองเมื่อกล้องตรง"), CamPrefs.grid, { CamPrefs.useGrid(it) }, icon = "grid3")
                PrismSwitchRow(
                    T("ภาพลื่นตลอด"), T("30 fps แม้ในที่มืด — ภาพจะมืดลงในห้องแสงน้อย"), CamPrefs.smooth,
                    { CamPrefs.useSmooth(it); onCameraChange() }, icon = "bolt",
                )
                PrismSwitchRow(T("ถนัดซ้าย"), T("ตอนถือแนวนอน ย้ายปุ่มถ่ายไปไว้ทางซ้าย"), CamPrefs.lefty, { CamPrefs.useLefty(it) }, icon = "hand")

                Spacer(Modifier.size(2.dp))
                // Info box (suite "About")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (c.isGlass) c.panel else c.fill).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrismIcon("info", size = 16.dp, tint = c.ink2)
                        Spacer(Modifier.width(8.dp))
                        PrismText(T("เกี่ยวกับ Prism Cam"), style = PrismThemeAccess.type.label, color = c.ink2)
                    }
                    PrismText(
                        "Prism Cam is a minimal, premium camera for phones and tablets — photos and video with Prism effects.",
                        style = PrismThemeAccess.type.caption, color = c.ink2,
                    )
                    Kv(T("เวอร์ชัน"), "${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}")
                    Kv(T("ผู้สร้าง"), Prism.AUTHOR)
                    Kv(T("สร้างด้วยโมเดล AI"), Prism.MODEL)
                    Kv("GitHub", com.prism.cam.update.Updater.REPO)
                    Kv(T("ที่เก็บรูป"), T("อัลบั้ม Prism Cam ในแกลเลอรี"))
                }
                UpdateBox()
            }
        }
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

/** Suite update zone: status line + Check / Update now / progress (same states as the desktop apps). */
@Composable
private fun UpdateBox() {
    val c = PrismThemeAccess.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st = com.prism.cam.update.Updater.state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val (msg, good) = when (st) {
            is com.prism.cam.update.Updater.State.Checking -> T("กำลังตรวจหาอัปเดต…") to null
            is com.prism.cam.update.Updater.State.Latest -> T("คุณใช้เวอร์ชันล่าสุดแล้ว") to true
            is com.prism.cam.update.Updater.State.Available -> T("มีเวอร์ชัน {} แล้ว (เครื่องนี้ {})", "v" + st.version, "v" + com.prism.cam.BuildConfig.VERSION_NAME) to true
            is com.prism.cam.update.Updater.State.Downloading -> T("กำลังดาวน์โหลด {}%", (com.prism.cam.update.Updater.progress * 100).toInt()) to null
            is com.prism.cam.update.Updater.State.Ready -> T("ดาวน์โหลดเสร็จ — กดติดตั้งในหน้าต่างของ Android") to true
            is com.prism.cam.update.Updater.State.Failed -> st.why to false
            else -> "" to null
        }
        if (msg.isNotEmpty()) PrismText(msg, style = PrismThemeAccess.type.caption, color = when (good) { true -> com.prism.cam.ui.theme.Prism.ok; false -> com.prism.cam.ui.theme.Prism.danger; else -> c.ink2 }, align = TextAlign.Center)
        if (st is com.prism.cam.update.Updater.State.Downloading) {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(c.segTrack)) {
                Box(Modifier.fillMaxWidth(com.prism.cam.update.Updater.progress.coerceAtLeast(.03f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(com.prism.cam.ui.theme.Prism.grad))
            }
        }
        when (st) {
            is com.prism.cam.update.Updater.State.Available -> com.prism.cam.ui.kit.PrismPill(T("อัปเดตเลย"), { scope.launch { com.prism.cam.update.Updater.download(ctx) } }, kind = com.prism.cam.ui.kit.PillKind.Primary, icon = "download")
            is com.prism.cam.update.Updater.State.Ready -> com.prism.cam.ui.kit.PrismPill(T("ติดตั้ง"), { com.prism.cam.update.Updater.install(ctx, st.file) }, kind = com.prism.cam.ui.kit.PillKind.Primary, icon = "download")
            is com.prism.cam.update.Updater.State.Checking, is com.prism.cam.update.Updater.State.Downloading -> {}
            else -> com.prism.cam.ui.kit.PrismPill(T("ตรวจหาอัปเดต"), { scope.launch { com.prism.cam.update.Updater.check() } }, icon = "refresh")
        }
    }
}
