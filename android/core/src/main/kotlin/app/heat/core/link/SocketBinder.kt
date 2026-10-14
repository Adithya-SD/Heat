package app.heat.core.link

import java.net.DatagramSocket
import java.net.Socket

/**
 * Platform hook: attach a socket to one particular network before it is used.
 *
 * It exists because a watch's default network is chosen to save battery, not to reach a camera on the local
 * Wi-Fi: left alone, a plain `Socket()` on a watch goes out over the phone's Bluetooth proxy, where a
 * `192.168.x.x` address leads nowhere. The Android layer implements this by binding to the Wi-Fi network it
 * asked for; everywhere else (the phone, tests) the default does nothing.
 */
interface SocketBinder {
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)

    object None : SocketBinder {
        override fun bind(socket: Socket) = Unit
        override fun bind(socket: DatagramSocket) = Unit
    }
}
