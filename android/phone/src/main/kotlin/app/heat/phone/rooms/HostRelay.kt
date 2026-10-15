package app.heat.phone.rooms

import android.content.Context
import android.util.Log
import app.heat.core.host.CameraHost
import app.heat.core.host.VideoSink
import app.heat.core.link.LinkState
import app.heat.core.model.AlertKind
import app.heat.core.model.HostInfo
import app.heat.core.model.Msg
import app.heat.phone.service.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * This phone as the hub. Every host whose code was added (other phones, browser tabs) stays joined as a viewer, so its
 * motion alerts always arrive. The watch only ever talks to this phone:
 *
 *  - alerts from every host are raised here, labelled with the host's name, and reach the watch whatever it is showing;
 *  - the video of ONE host is forwarded at a time (the phone's own camera, or one of the others), and that host is
 *    asked for video only while the watch wants it, so the rest cost nothing but their alerts;
 *  - motion on a host puts that host on show, unless the view is locked to one;
 *  - the watch can ask for any host ([CameraHost.onPick]) and pin the view there.
 *
 * Which host is shown and whether the view is locked are remembered.
 */
class HostRelay(
    context: Context,
    private val scope: CoroutineScope,
    private val host: CameraHost,
    private val rooms: RoomManager,
    /** The phone's own encoder must send a keyframe: its picture is what the watch is switching back to. */
    private val localKeyframe: () -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("heat-relay", Context.MODE_PRIVATE)

    private inner class Entry(val code: String, val viewer: RoomViewer) {
        @Volatile var name: String = ""
        @Volatile var live = false
        @Volatile var lastAlertMs = 0L
        @Volatile var status: Msg.Status? = null
        @Volatile var watching = false
        @Volatile var uiViewing = false
        val jobs = mutableListOf<Job>()
        fun label() = name.ifBlank { "Camera ${code.take(4)}" }
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var order: List<String> = emptyList()
    private val lock = Any()

    @Volatile private var shown: String = prefs.getString(KEY_SHOWN, HostInfo.SELF) ?: HostInfo.SELF
    @Volatile private var locked: Boolean = prefs.getBoolean(KEY_LOCKED, false)
    @Volatile private var wantVideo = false
    @Volatile private var lastLocalAlertMs = 0L

    /** True while the picture the watch gets is this phone's own camera. */
    val showingSelf: Boolean get() = shown == HostInfo.SELF || !entries.containsKey(shown)

    fun start() {
        host.onPick = { id, lockWanted -> pick(id, lockWanted) }
        host.armHook = { armed, delay -> forwardArm(armed, delay) }
        scope.launch { rooms.recent.collect { sync(it) } }
        scope.launch { while (true) { delay(1_000); publish() } }
        publish()
    }

    fun shutdown() {
        host.onPick = null
        host.armHook = null
        host.statusOverride = null
        for (e in entries.values) stop(e)
        entries.clear()
    }

    // ── hosts ────────────────────────────────────────────────────────

    private suspend fun sync(codes: List<String>) {
        synchronized(lock) { order = codes }
        for (c in codes) if (!entries.containsKey(c)) runCatching { add(c) }.onFailure { Log.w(TAG, "could not join $c", it) }
        for (c in entries.keys.toList()) if (c !in codes) remove(c)
        publish()
    }

    private suspend fun add(code: String) {
        val v = rooms.join(code)
        val e = Entry(code, v)
        if (entries.putIfAbsent(code, e) != null) { v.stop(); return }
        v.tap = VideoSink { flags, pts, data, off, len -> if (shown == code) host.sendVideo(flags, pts, data, off, len, CameraHost.Audience.HUB) }
        v.rawThumb = { jpeg -> host.sendThumbnail(jpeg) }
        v.start(video = false)
        val c = v.client
        e.jobs += scope.launch { c.alerts.collect { onAlert(e, it) } }
        e.jobs += scope.launch { c.linkState.collect { e.live = it.phase == LinkState.Phase.UP; publish() } }
        e.jobs += scope.launch {
            c.config.collect { cfg -> cfg?.label?.takeIf { it.isNotBlank() }?.let { if (e.name != it) { e.name = it; publish() } } }
        }
        e.jobs += scope.launch {
            c.hello.collect { h -> if (e.name.isBlank() && !h?.name.isNullOrBlank()) { e.name = h!!.name; publish() } }
        }
        e.jobs += scope.launch {
            var geometry: Any? = null
            c.status.collect { st ->
                e.status = st
                val g = st?.stream
                if (g != geometry) { geometry = g; if (shown == code) host.refreshStatus() }
            }
        }
        Log.i(TAG, "hub: joined host ${code.take(4)}…")
    }

    private fun remove(code: String) {
        val e = entries.remove(code) ?: return
        stop(e)
        if (shown == code) setShown(HostInfo.SELF)
        Log.i(TAG, "hub: left host ${code.take(4)}…")
    }

    private fun stop(e: Entry) {
        e.jobs.forEach { it.cancel() }
        e.viewer.tap = null
        e.viewer.rawThumb = null
        e.viewer.stop()
    }

    // ── alerts and switching ─────────────────────────────────────────

    private fun onAlert(e: Entry, a: Msg.Alert) {
        e.lastAlertMs = System.currentTimeMillis()
        host.raiseAlert(a.kind, a.changedPct, a.escalation, e.code, e.label())
        if (a.kind == AlertKind.MOTION) {
            AlertNotifier.motion(appContext, host.config.value.copy(label = e.label()), host.viewerCount.value)
            if (!locked && shown != e.code) {
                Log.i(TAG, "hub: motion on ${e.label()}: showing it")
                setShown(e.code)
            }
        }
        publish()
    }

    /** The phone's own camera saw movement: back to it, unless the view is locked on another host. */
    fun onLocalMotion() {
        lastLocalAlertMs = System.currentTimeMillis()
        if (!locked && shown != HostInfo.SELF) setShown(HostInfo.SELF)
    }

    private fun pick(id: String, lockWanted: Boolean?) {
        if (lockWanted != null && lockWanted != locked) {
            locked = lockWanted
            prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
        }
        setShown(id)
        publish()
    }

    private fun setShown(id: String) {
        synchronized(lock) {
            val target = if (id == HostInfo.SELF || entries.containsKey(id)) id else HostInfo.SELF
            if (target == shown) return
            val old = entries[shown]
            shown = target
            prefs.edit().putString(KEY_SHOWN, shown).apply()
            val now = entries[shown]
            host.statusOverride = if (now == null) null else { own -> overlay(own, now) }
            old?.let { interest(it) }
            if (now == null) localKeyframe() else interest(now, forceKey = true)
        }
        host.refreshStatus()
        publish()
    }

    /** What the status says while another host's picture is relayed: that host's picture size, armed state and lenses. */
    private fun overlay(own: Msg.Status, e: Entry): Msg.Status {
        val s = e.status ?: return own.copy(streaming = false, stream = null)
        return own.copy(
            armed = s.armed, armingIn = s.armingIn, streaming = s.streaming, stream = s.stream,
            lenses = s.lenses, activeLens = s.activeLens, lensLabel = s.lensLabel, digitalZoom = s.digitalZoom,
            hasTorch = false, manualFocus = false, manualExposure = false, error = s.error,
            battery = if (s.battery >= 0) s.battery else own.battery, charging = s.charging
        )
    }

    private fun forwardArm(armed: Boolean, delaySec: Int?): Boolean {
        val e = entries[shown] ?: return false
        e.viewer.client.arm(armed, delaySec)
        return true
    }

    // ── video interest ───────────────────────────────────────────────

    /** The watch (or any viewer) wants, or no longer wants, video from this phone. */
    fun setWantVideo(want: Boolean) {
        wantVideo = want
        for (e in entries.values) interest(e)
    }

    /** A viewer lost sync: the keyframe has to come from whichever host is on show. */
    fun requestKeyframe(): Boolean {
        val e = entries[shown] ?: return false
        e.viewer.client.needKeyframe()
        return true
    }

    private fun interest(e: Entry, forceKey: Boolean = false) {
        val want = (shown == e.code && wantVideo) || e.uiViewing
        if (e.watching != want) {
            e.watching = want
            e.viewer.client.watch(video = want)
        }
        if (want && forceKey) e.viewer.client.needKeyframe()
    }

    // ── the phone's own screen looking at a host ─────────────────────

    /** The viewer for a host's code, joined if it was not. Null if it could not be reached in time. */
    suspend fun viewerFor(code: String): RoomViewer? {
        rooms.remember(code)
        repeat(60) {
            entries[code]?.let { return it.viewer }
            delay(250)
        }
        return null
    }

    /** The phone's screen is, or is no longer, showing this host. */
    fun viewing(code: String, on: Boolean) {
        val e = entries[code] ?: return
        e.uiViewing = on
        e.viewer.decoding = on
        interest(e, forceKey = on)
    }

    // ── what the watch is told ───────────────────────────────────────

    private fun publish() {
        val list = ArrayList<HostInfo>()
        list += HostInfo(HostInfo.SELF, host.config.value.label, live = true, armed = host.armed.value, lastAlertMs = lastLocalAlertMs)
        val codes = synchronized(lock) { order }
        for (c in codes) entries[c]?.let { e ->
            list += HostInfo(e.code, e.label(), live = e.live, armed = e.status?.armed == true, lastAlertMs = e.lastAlertMs)
        }
        host.setHosts(Msg.Hosts(list, if (showingSelf) HostInfo.SELF else shown, locked))
    }

    private companion object {
        const val TAG = "HeatHostRelay"
        const val KEY_SHOWN = "shown"
        const val KEY_LOCKED = "locked"
    }
}
