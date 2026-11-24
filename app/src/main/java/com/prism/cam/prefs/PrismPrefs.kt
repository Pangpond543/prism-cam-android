package com.prism.cam.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prism.cam.ui.theme.Look

/**
 * The three shell settings every Prism app shares (same keys as the desktop localStorage):
 * prism.lang (th|en), prism.theme (light|dark), prism.look (classic|glass).
 * Read synchronously once at start (tiny file) so the first frame already has the right look — no flash.
 * Defaults: Thai, light, **classic** (docs/plan.md — Classic is the Android default).
 */
object PrismPrefs {
    private const val FILE = "prism"
    private lateinit var app: Context

    var lang by mutableStateOf("th"); private set
    var dark by mutableStateOf(false); private set
    var look by mutableStateOf(Look.Classic); private set

    fun init(context: Context) {
        app = context.applicationContext
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        lang = if (p.getString("prism.lang", "th") == "en") "en" else "th"
        dark = p.getString("prism.theme", "light") == "dark"
        look = if (p.getString("prism.look", "classic") == "glass") Look.Glass else Look.Classic
    }

    private fun save(key: String, value: String) {
        if (!::app.isInitialized) return
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    fun useLang(v: String) { lang = if (v == "en") "en" else "th"; save("prism.lang", lang) }
    fun useDark(v: Boolean) { dark = v; save("prism.theme", if (v) "dark" else "light") }
    fun useLook(v: Look) { look = v; save("prism.look", if (v == Look.Glass) "glass" else "classic") }
}
