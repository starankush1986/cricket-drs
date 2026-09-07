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
    private val _lastError = MutableStateFlow<String?>(null)

    val localConnected: StateFlow<Boolean> = _localConnected
    val liveConnected: StateFlow<Boolean> = _liveConnected
    val sendingEnabled: StateFlow<Boolean> = _sendingEnabled
    val lastError: StateFlow<String?> = _lastError

    private var localSocket: Socket? = null
    private var liveSocket: Socket? = null

    fun connect() {
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
                emitSenderStatus(_sendingEnabled.value)
            }
            on(Socket.EVENT_DISCONNECT) {
                _localConnected.value = false
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                _localConnected.value = false
                _lastError.value = args.firstOrNull()?.toString()
            }
            connect()
        }

        liveSocket = IO.socket(URI.create(liveUrl), opts).apply {
            on(Socket.EVENT_CONNECT) {
                _liveConnected.value = true
                if (_sendingEnabled.value) emit("iam_sender")
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
        if (!_sendingEnabled.value) return false

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

    fun disconnect() {
        localSocket?.off()
        liveSocket?.off()
        localSocket?.disconnect()
        liveSocket?.disconnect()
        localSocket = null
        liveSocket = null
        _localConnected.value = false
        _liveConnected.value = false
    }

    private fun emitSenderStatus(enabled: Boolean) {
        if (localSocket?.connected() == true) {
            localSocket?.emit("sender_status", JSONObject().put("enabled", enabled))
        }
        if (enabled && liveSocket?.connected() == true) {
            liveSocket?.emit("iam_sender")
        }
    }
}
