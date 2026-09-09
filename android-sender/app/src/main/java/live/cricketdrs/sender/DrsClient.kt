package live.cricketdrs.sender

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI

class DrsClient(
    private val localUrl: String = "https://cricketdrs.com",
    private val liveUrl: String = "https://api.cricketking.live"
) {
    private val _localConnected = MutableStateFlow(false)
    private val _liveConnected = MutableStateFlow(false)
    private val _sendingEnabled = MutableStateFlow(true)
    private val _joined = MutableStateFlow(false)
    private val _matchId = MutableStateFlow("")
    private val _lastError = MutableStateFlow<String?>(null)

    val localConnected: StateFlow<Boolean> = _localConnected
    val liveConnected: StateFlow<Boolean> = _liveConnected
    val sendingEnabled: StateFlow<Boolean> = _sendingEnabled
    val joined: StateFlow<Boolean> = _joined
    val matchId: StateFlow<String> = _matchId
    val lastError: StateFlow<String?> = _lastError

    private var localSocket: Socket? = null
    private var liveSocket: Socket? = null

    fun normalizePin(raw: String): String =
        raw.filter { it.isDigit() }.take(4)

    fun connect(pinRaw: String) {
        val pin = normalizePin(pinRaw)
        if (pin.length != 4) {
            _lastError.value = "4 digit code daalo"
            return
        }
        disconnect()
        _matchId.value = pin
        _joined.value = false

        val opts = IO.Options().apply {
            transports = arrayOf("websocket", "polling")
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 500
            reconnectionDelayMax = 5000
            timeout = 20000
        }

        localSocket = IO.socket(URI.create(localUrl), opts).apply {
            on(Socket.EVENT_CONNECT) {
                _localConnected.value = true
                _lastError.value = null
                emit("join", JSONObject().put("matchId", _matchId.value))
            }
            on("joined") { args ->
                val data = args.firstOrNull() as? JSONObject
                val ok = data?.optString("matchId") == _matchId.value
                _joined.value = ok
                if (ok) emitSenderStatus(_sendingEnabled.value)
            }
            on("join_error") { args ->
                _joined.value = false
                val data = args.firstOrNull() as? JSONObject
                _lastError.value = data?.optString("message").orEmpty()
                    .ifBlank { "Code match nahi hua" }
            }
            on(Socket.EVENT_DISCONNECT) {
                _localConnected.value = false
                _joined.value = false
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                _localConnected.value = false
                _joined.value = false
                _lastError.value = args.firstOrNull()?.toString()
            }
            connect()
        }

        liveSocket = IO.socket(URI.create(liveUrl), opts).apply {
            on(Socket.EVENT_CONNECT) {
                _liveConnected.value = true
                if (_sendingEnabled.value && _joined.value) emit("iam_sender")
            }
            on(Socket.EVENT_DISCONNECT) {
                _liveConnected.value = false
            }
            connect()
        }
    }

    fun setSendingEnabled(enabled: Boolean) {
        _sendingEnabled.value = enabled
        emitSenderStatus(enabled)
    }

    fun sendEvent(eventName: String): Boolean {
        if (!_sendingEnabled.value || !_joined.value) return false

        val localOk = localSocket?.connected() == true
        val liveOk = liveSocket?.connected() == true
        if (!localOk && !liveOk) {
            _lastError.value = "Server connect nahi hai. Thoda wait karo."
            return false
        }

        val eventData = JSONObject()
            .put("type", "EVENT")
            .put("payload", JSONObject().put("event", eventName))

        if (localOk) localSocket?.emit("broadcast", eventData)
        if (liveOk) liveSocket?.emit("event", eventData)
        _lastError.value = null
        return true
    }

    fun leaveMatch() {
        disconnect()
        _matchId.value = ""
        _joined.value = false
    }

    fun disconnect() {
        localSocket?.off()
        liveSocket?.off()
        localSocket?.disconnect()
        liveSocket?.disconnect()
        localSocket = null
        liveSocket = null
        _localConnected.value = false
        _liveConnected.value = false
        _joined.value = false
    }

    private fun emitSenderStatus(enabled: Boolean) {
        if (localSocket?.connected() == true && _joined.value) {
            localSocket?.emit("sender_status", JSONObject().put("enabled", enabled))
        }
        if (enabled && liveSocket?.connected() == true && _joined.value) {
            liveSocket?.emit("iam_sender")
        }
    }
}
