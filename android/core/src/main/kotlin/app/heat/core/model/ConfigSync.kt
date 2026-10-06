package app.heat.core.model

import kotlinx.serialization.json.JsonObject

/**
 * The camera's side of settings: owns the truth, applies patches, numbers
 * revisions, remembers what each viewer has had applied.
 *
 * Not thread-safe by itself on purpose: the camera service funnels every
 * mutation through one coroutine, so there is exactly one writer.
 */
class ConfigHost(initial: CameraConfig, initialRev: Long = 0) {
    var config: CameraConfig = initial.sanitized()
        private set
    var rev: Long = initialRev
        private set

    /**
     * Applies a viewer's patch. Returns false if the patch was invalid and
     * ignored. A patch that changes nothing still acknowledges, so the viewer
     * can retire its pending copy.
     */
    fun apply(patch: JsonObject): Boolean {
        val next = ConfigPatch.apply(config, patch) ?: return false
        if (next != config) {
            config = next
            rev++
        }
        return true
    }

    /** Replace the whole config (local UI on the camera itself). */
    fun replace(next: CameraConfig) {
        val s = next.sanitized()
        if (s != config) {
            config = s
            rev++
        }
    }

    fun state(ackSeq: Int): Msg.ConfigState = Msg.ConfigState(rev, ackSeq, config)
}

/**
 * The viewer's side of settings for ONE camera.
 *
 * What the UI shows is [effective]: the camera's last echoed config with this
 * viewer's not-yet-acknowledged edits laid over it. Drag a slider and it moves
 * instantly; if the camera disagrees (clamps it, or another viewer won) the
 * echo corrects the UI within one round trip, and nothing can get stuck,
 * because a pending edit is dropped the moment the camera acknowledges it.
 *
 * After a reconnect, [onSessionUp] returns the edits that were never
 * acknowledged so a slider move made during a blip is not lost.
 */
class ConfigSync {
    private class Pending(val seq: Int, val patch: JsonObject)

    private val lock = Any()
    private var remote: CameraConfig? = null
    private var remoteRev = -1L
    private val pending = ArrayList<Pending>()
    private var nextSeq = 1

    /** What to render. Null until the camera has sent its config. */
    val effective: CameraConfig?
        get() = synchronized(lock) { compute() }

    val revision: Long get() = synchronized(lock) { remoteRev }

    /**
     * Always accepted: within one session messages arrive in order, so the
     * newest is the truth, and comparing revisions would wedge the UI forever if
     * the camera ever restarted with a lower one.
     */
    fun onRemote(state: Msg.ConfigState) = synchronized(lock) {
        remote = state.config
        remoteRev = state.rev
        pending.removeAll { it.seq <= state.ackSeq }
    }

    /**
     * Records an edit and returns the message to send, or null if it changes
     * nothing. Returns null too before the camera's config is known (there is
     * nothing to diff against, and silently inventing a base would send the
     * camera values the user never chose).
     */
    fun edit(transform: (CameraConfig) -> CameraConfig): Msg.SetConfig? = synchronized(lock) {
        val current = compute() ?: return null
        val wanted = transform(current).sanitized()
        val patch = ConfigPatch.diff(current, wanted)
        if (patch.isEmpty()) return null
        val p = Pending(nextSeq++, patch)
        pending += p
        Msg.SetConfig(p.seq, p.patch)
    }

    /** New session with the same camera: renumber and resend everything not yet acknowledged. */
    fun onSessionUp(): List<Msg.SetConfig> = synchronized(lock) {
        val old = pending.toList()
        pending.clear()
        nextSeq = 1
        old.map { Pending(nextSeq++, it.patch) }.also { pending += it }.map { Msg.SetConfig(it.seq, it.patch) }
    }

    private fun compute(): CameraConfig? {
        var c = remote ?: return null
        for (p in pending) c = ConfigPatch.apply(c, p.patch) ?: c
        return c
    }
}
