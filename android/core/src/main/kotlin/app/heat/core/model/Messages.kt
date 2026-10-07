package app.heat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a device is for in this conversation. A phone can be a camera and a viewer at once, in different sessions. */
@Serializable
enum class Role { CAMERA, VIEWER, HUB }

/** Highest video tier a viewer wants. The camera also caps by link, battery and temperature. */
@Serializable
enum class Tier { LOW, BALANCED, HIGH, MAX }

@Serializable
enum class AlertKind { MOTION, TAMPER, LOW_BATTERY, OVERHEAT, CAMERA_ERROR }

@Serializable
data class StreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val codec: String = "avc",
    /** Degrees clockwise to rotate the picture for it to appear upright (the sensor is mounted sideways). */
    val rotation: Int = 0
)

@Serializable
data class LensInfo(val id: String, val label: String, val ratio: Float)

/**
 * Every control-plane message, in both directions. JSON, tagged by `t`.
 *
 * Unknown tags and unknown fields are ignored on receipt, so a newer app and
 * an older one keep talking: that is the whole versioning strategy for the
 * control plane. (The binary media plane is versioned by the handshake.)
 */
@Serializable
sealed interface Msg {

    /** First message in each direction after the handshake. */
    @Serializable @SerialName("hello")
    data class Hello(
        val name: String,
        val role: Role,
        val app: String,
        val features: List<String> = emptyList()
    ) : Msg

    // ── viewer → camera ──────────────────────────────────────────────

    /** Merge [patch] into the camera's config. [seq] is echoed in [ConfigState.ackSeq]. */
    @Serializable @SerialName("set")
    data class SetConfig(val seq: Int, val patch: JsonObject) : Msg

    @Serializable @SerialName("arm")
    data class Arm(val armed: Boolean, val delaySec: Int? = null) : Msg

    /** What this viewer wants delivered right now. Sent whenever it changes; the latest one wins. */
    @Serializable @SerialName("watch")
    data class Watch(val video: Boolean, val audio: Boolean = false, val tier: Tier = Tier.MAX) : Msg

    /** Decoder lost sync or just attached: send a keyframe now. */
    @Serializable @SerialName("key")
    data object NeedKey : Msg

    @Serializable @SerialName("snap")
    data object Snap : Msg

    /** Make the camera blink, beep, or otherwise identify itself. */
    @Serializable @SerialName("id")
    data object Identify : Msg

    // ── camera → viewer ──────────────────────────────────────────────

    /** Full config plus the highest [SetConfig.seq] of the receiving viewer that has been applied. */
    @Serializable @SerialName("cfg")
    data class ConfigState(val rev: Long, val ackSeq: Int, val config: CameraConfig) : Msg

    @Serializable @SerialName("status")
    data class Status(
        val armed: Boolean,
        /** Seconds until armed, while an arming countdown runs. */
        val armingIn: Int = 0,
        val battery: Int = -1,
        val charging: Boolean = false,
        /** 0 nominal, 1 throttling, 2 critical. */
        val thermal: Int = 0,
        val streaming: Boolean = false,
        val stream: StreamInfo? = null,
        val lenses: List<LensInfo> = emptyList(),
        val activeLens: String? = null,
        /** Label of the lens producing the picture, e.g. "1x" or "0.5x". */
        val lensLabel: String = "",
        /** True when the zoom is past the lens's own reach, i.e. a crop of the sensor. */
        val digitalZoom: Boolean = false,
        val hasTorch: Boolean = false,
        val recording: Boolean = false,
        val storageUsedMb: Int = 0,
        val events: Long = 0,
        val error: String? = null
    ) : Msg

    @Serializable @SerialName("alert")
    data class Alert(
        val id: Long,
        val kind: AlertKind,
        val atMs: Long,
        val changedPct: Float = 0f,
        val escalation: Int = 0
    ) : Msg
}

/** Encode/decode helper that never throws on bad input. */
object MsgCodec {
    fun encode(msg: Msg): ByteArray = ProtoJson.encodeToString(Msg.serializer(), msg).toByteArray(Charsets.UTF_8)

    /** Null for anything that is not a valid message we understand (including newer, unknown types). */
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Msg? = runCatching {
        ProtoJson.decodeFromString(Msg.serializer(), String(bytes, offset, length, Charsets.UTF_8))
    }.getOrNull()
}
