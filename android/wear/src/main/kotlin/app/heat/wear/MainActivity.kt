package app.heat.wear

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.heat.wear.ui.WatchRoot

/**
 * Where the bezel's input is picked up, and what it is handed to.
 *
 * The camera screen registers a [sink] while it is showing; the activity feeds it every rotary event in
 * detents (positive = clockwise). Taking the events here, before any view sees them, means turning the
 * bezel can never be lost to a focus change: a tap, a recomposition or a dialog cannot leave the bezel
 * talking to nobody, which is the commonest reason a rotary control "stops working" until you touch it.
 */
object Rotary {
    @Volatile var sink: ((Float) -> Unit)? = null

    // For the diagnostics page: proof, on the wrist, that every click is arriving.
    @Volatile var events = 0L
        private set
    @Volatile var lastRaw = 0f
        private set

    internal fun deliver(detents: Float): Boolean {
        events++
        lastRaw = detents
        val s = sink ?: return false
        s(detents)
        return true
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var hub: WatchHub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Someone looking at a camera does not want the screen to doze mid-glance.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hub = (application as HeatWearApp).hub
        setContent { WatchRoot(hub) }
    }

    override fun onStart() {
        super.onStart()
        hub.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        hub.refresh()
    }

    override fun onStop() {
        hub.setForeground(false)
        super.onStop()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            // AXIS_SCROLL is negative for clockwise; flip it so clockwise is positive, the way every control here reads it.
            if (Rotary.deliver(-ev.getAxisValue(MotionEvent.AXIS_SCROLL))) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
