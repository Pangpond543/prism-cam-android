plugins {
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 has Kotlin built in; this plugin also pins the Kotlin version (keep them equal).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Build output never lands in OneDrive (the source folder) — see docs/pitfalls.md.
val prismOut = File(System.getenv("PRISM_BUILD_DIR") ?: (System.getProperty("user.home") + "/PrismCamAndroidBuild"))
allprojects {
    layout.buildDirectory.set(File(prismOut, "build/" + (if (this == rootProject) "_root" else name)))
}
