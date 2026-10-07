package app.heat.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.heat.wear.ui.WatchRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Someone looking at a camera does not want the screen to doze mid-glance.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hub = (application as HeatWearApp).hub
        setContent { WatchRoot(hub) }
    }

    override fun onResume() {
        super.onResume()
        (application as HeatWearApp).hub.refresh()
    }
}
