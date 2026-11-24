package com.prism.cam.i18n

import com.prism.cam.prefs.PrismPrefs

/**
 * Thai is the source text, English is looked up by the Thai string (same mechanism as the desktop suite's
 * `T\`…\`` + lang-en.js). Reading PrismPrefs.lang inside a composable makes the screen switch language at once.
 * Placeholders: "{}" in both strings, filled in order by [T] varargs.
 * Every new Thai string needs an entry in [En.map] — tools/i18n-audit.sh lists the missing ones.
 */
fun T(th: String, vararg args: Any): String {
    val s = if (PrismPrefs.lang == "en") En.map[th] ?: th else th
    if (args.isEmpty()) return s
    val b = StringBuilder()
    var i = 0
    var from = 0
    while (true) {
        val at = s.indexOf("{}", from)
        if (at < 0 || i >= args.size) { b.append(s, from, s.length); break }
        b.append(s, from, at).append(args[i++])
        from = at + 2
    }
    return b.toString()
}
