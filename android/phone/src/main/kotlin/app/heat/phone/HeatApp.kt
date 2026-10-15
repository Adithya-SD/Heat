package app.heat.phone

import android.app.Application
import app.heat.phone.service.CameraService
import app.heat.platform.PeerLauncher

class HeatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The watch's power button (or this app's own) ends the operation: the camera service stops with the app.
        PeerLauncher.closer = { ctx -> CameraService.stop(ctx) }
        // Opening the watch app starts the phone's camera as a background task: nothing on screen, the screen not woken.
        // The one camera (the hub in the service) is what the app's own screen shows too, so the two can never fight.
        PeerLauncher.quietOpen = { ctx -> app.heat.phone.service.QuietStart.begin(ctx) }
    }
}
