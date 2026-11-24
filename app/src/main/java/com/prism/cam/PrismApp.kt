package com.prism.cam

import android.app.Application
import androidx.camera.lifecycle.ProcessCameraProvider
import com.prism.cam.prefs.PrismPrefs

/**
 * Starts the camera service as early as possible: CameraX initialisation (~100–300 ms) runs in parallel with the
 * activity and Compose start-up, so the first frame shows sooner (docs/plan.md target: picture in < 0.5–0.8 s).
 */
class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrismPrefs.init(this)
        com.prism.cam.prefs.CamPrefs.init(this)
        PrismLog.start(this)
        PrismLog.mark("app")
        ProcessCameraProvider.getInstance(this) // warm-up; the activity awaits the same instance
    }
}
