package app.heat.phone

import android.app.Application
import app.heat.phone.service.CameraService
import app.heat.platform.PeerLauncher

class HeatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The watch's power button (or this app's own) ends the operation: the camera service stops with the app.
        PeerLauncher.closer = { ctx -> CameraService.stop(ctx) }
    }
}
