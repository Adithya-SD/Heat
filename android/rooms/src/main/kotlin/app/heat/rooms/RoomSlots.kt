package app.heat.rooms

import android.util.Log
import app.heat.core.link.Acceptor
import app.heat.core.room.RoomCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "HeatRoomSlots"

/**
 * A host in a room that can hold several. It claims the first free slot (0 is the room id itself, so a room that has
 * only ever had one host looks exactly like before), and keeps it. If every slot is taken the room is full.
 *
 * [patience] is how many "taken" answers a slot gets before the next is tried. A restarted app finds its own old
 * registration still on the server for a few seconds, so the first slot gets longer than the rest.
 */
class RoomSlotHost(
    private val net: RoomNet,
    private val roomId: String,
    private val acceptor: Acceptor,
    private val firstPatience: Int = 6
) {
    data class State(val phase: RoomHost.Phase = RoomHost.Phase.CONNECTING, val slot: Int = -1)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var host: RoomHost? = null

    fun start() {
        if (job != null) return
        job = net.scope.launch { run() }
    }

    fun stop() {
        job?.cancel(); job = null
        host?.stop(); host = null
    }

    private suspend fun run() {
        var slot = 0
        while (true) {
            val h = RoomHost(net, RoomCode.slotId(roomId, slot), acceptor, takenAfter = if (slot == 0) firstPatience else 2)
            host = h
            h.start()
            var mine = false
            // Wait until this slot is ours, or taken by someone else.
            while (true) {
                val p = h.state.value.phase
                _state.value = State(p, slot)
                if (p == RoomHost.Phase.TAKEN) break
                if (p == RoomHost.Phase.READY) mine = true
                if (mine) { h.state.collect { _state.value = State(it.phase, slot) } }
                delay(200)
            }
            h.stop()
            slot++
            if (slot >= RoomCode.SLOTS) { _state.value = State(RoomHost.Phase.TAKEN, -1); Log.w(TAG, "room $roomId is full"); return }
            Log.i(TAG, "slot ${slot - 1} is taken, trying $slot")
        }
    }
}

/**
 * Which host slots of a room have somebody in them. One signaling socket, no video setup: an offer is sent to each
 * slot id, and the server answers "expired" at once for an id nobody holds. A slot that stays quiet is occupied. (A
 * host that does get the dummy offer just fails to answer it and carries on.)
 */
internal suspend fun probeRoom(net: RoomNet, roomId: String, slots: IntRange = 0 until RoomCode.SLOTS): Set<Int> {
    val signal = PeerSignal(randomId(), net.scope, net.http, net.server)
    return try {
        signal.open(5_000)
        val ids = slots.associateBy { RoomCode.slotId(roomId, it) }
        for (id in ids.keys) signal.send("OFFER", id, PeerJsCodec.offer("v=0\r\n", "dc_probe_" + randomId(6)))
        val absent = HashSet<String>()
        withTimeoutOrNull(1_800) {
            for (m in signal.incoming) if (m.type == "EXPIRE" && m.src != null) { absent += m.src; if (absent.size == ids.size) break }
        }
        ids.filterKeys { it !in absent }.values.toSet()
    } catch (e: SignalException) {
        emptySet()
    } finally {
        signal.close()
    }
}

/** [probeRoom] for callers outside this module. */
suspend fun scanRoom(net: RoomNet, roomId: String): Set<Int> = probeRoom(net, roomId)
