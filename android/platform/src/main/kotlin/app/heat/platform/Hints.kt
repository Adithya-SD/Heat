package app.heat.platform

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Things the app learned last time that make starting faster this time: what it will *assume* until the real
 * answer arrives, and verify afterwards. Every entry is a guess that is cheap to be wrong about:
 *
 *  - the Bluetooth address of the phone that answered, so the next dial goes straight to it;
 *  - the picture geometry of each camera, so the decoder can be built before the camera has said a word;
 *  - when a Wi-Fi route last worked, so the radio is asked for early.
 *
 * Losing this file costs nothing but the head start.
 */
class Hints(context: Context) {
    @Serializable
    data class Geometry(val width: Int, val height: Int, val rotation: Int)

    @Serializable
    private data class Data(
        val btAddress: String? = null,
        val geometry: Map<String, Geometry> = emptyMap(),
        val lastWifiMs: Long = 0
    )

    private val file = File(File(context.filesDir, "heat").apply { mkdirs() }, "hints.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var data: Data = runCatching { json.decodeFromString<Data>(file.readText()) }.getOrDefault(Data())

    var btAddress: String?
        get() = data.btAddress
        set(value) = update { it.copy(btAddress = value) }

    fun geometry(camera: String): Geometry? = data.geometry[camera]

    fun setGeometry(camera: String, g: Geometry) {
        if (data.geometry[camera] != g) update { it.copy(geometry = it.geometry + (camera to g)) }
    }

    /** True if a Wi-Fi connection to a camera worked within the last [withinMs]. */
    fun wifiWorkedRecently(now: Long, withinMs: Long = 24 * 3_600_000L): Boolean = data.lastWifiMs != 0L && now - data.lastWifiMs < withinMs

    fun noteWifiWorked(now: Long) {
        if (now - data.lastWifiMs > 60_000) update { it.copy(lastWifiMs = now) }
    }

    @Synchronized
    private fun update(f: (Data) -> Data) {
        val next = f(data)
        if (next == data) return
        data = next
        runCatching { Vault.atomicWrite(file, json.encodeToString(next).toByteArray()) }
    }
}
