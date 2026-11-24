package com.prism.cam.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Camera choices the app remembers between starts (good camera apps keep your timer / grid / shape).
 * Same SharedPreferences file as the shell ("prism"), keys `cam.*`. Read once at start.
 * Setters are `use…` — `set…` next to a state property breaks the Kotlin build (docs/pitfalls.md).
 */
object CamPrefs {
    enum class Aspect(val key: String, val long: Float, val label: String) {
        R4_3("4:3", 4f / 3f, "4:3"), R16_9("16:9", 16f / 9f, "16:9"), R1_1("1:1", 1f, "1:1");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: R4_3 }
    }

    private lateinit var app: Context

    /** 0 / 3 / 10 seconds */
    var timer by mutableIntStateOf(0); private set
    var grid by mutableStateOf(false); private set
    var aspect by mutableStateOf(Aspect.R4_3); private set
    /** steady 30 fps in photo mode even in dim rooms (darker picture) */
    var smooth by mutableStateOf(false); private set
    /** landscape: shutter column on the left */
    var lefty by mutableStateOf(false); private set
    var cleanHintShown by mutableStateOf(false); private set

    fun init(context: Context) {
        app = context.applicationContext
        val p = app.getSharedPreferences("prism", Context.MODE_PRIVATE)
        timer = p.getInt("cam.timer", 0).let { if (it == 3 || it == 10) it else 0 }
        grid = p.getBoolean("cam.grid", false)
        aspect = Aspect.of(p.getString("cam.aspect", null))
        smooth = p.getBoolean("cam.smooth", false)
        lefty = p.getBoolean("cam.lefty", false)
        cleanHintShown = p.getBoolean("cam.cleanHint", false)
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        if (!::app.isInitialized) return
        app.getSharedPreferences("prism", Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    fun cycleTimer() { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }; edit { putInt("cam.timer", timer) } }
    fun useTimer(s: Int) { timer = if (s == 3 || s == 10) s else 0; edit { putInt("cam.timer", timer) } }
    fun useGrid(v: Boolean) { grid = v; edit { putBoolean("cam.grid", v) } }
    fun cycleAspect() { useAspect(when (aspect) { Aspect.R4_3 -> Aspect.R16_9; Aspect.R16_9 -> Aspect.R1_1; Aspect.R1_1 -> Aspect.R4_3 }) }
    fun useAspect(a: Aspect) { aspect = a; edit { putString("cam.aspect", a.key) } }
    fun useSmooth(v: Boolean) { smooth = v; edit { putBoolean("cam.smooth", v) } }
    fun useLefty(v: Boolean) { lefty = v; edit { putBoolean("cam.lefty", v) } }
    fun markCleanHint() { cleanHintShown = true; edit { putBoolean("cam.cleanHint", true) } }
}
