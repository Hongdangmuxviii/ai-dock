package com.aidock.dock

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DockState(
    val connection: Connection = Connection.Searching,
    val providers: List<Provider> = emptyList(),
    /** PC 시계 - 기기 시계 (초). 초기화 카운트다운과 재생 위치 계산에 씁니다. */
    val clockOffset: Double = 0.0,
    val media: Media = Media(),
    val thumbnail: ImageBitmap? = null,
    val manualAddress: String = "",
    val token: String = "",
)

class DockViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        DockState(
            providers = loadCachedProviders(),
            clockOffset = prefs.getFloat("clock_offset", 0f).toDouble(),
            manualAddress = prefs.getString("manual_address", "") ?: "",
            token = prefs.getString("token", "") ?: "",
        )
    )
    val state: StateFlow<DockState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var discovery: Discovery? = null

    init {
        start()
    }

    // ---------------- 연결 ----------------

    private fun start() {
        connectJob?.cancel()
        socket?.cancel()
        discovery?.stop()
        val manual = _state.value.manualAddress.trim()
        if (manual.isNotEmpty()) {
            connectLoop(if (":" in manual) manual else "$manual:8765")
        } else {
            _state.update { it.copy(connection = Connection.Searching) }
            discovery = Discovery(getApplication()) { host, port ->
                viewModelScope.launch { connectLoop("$host:$port") }
            }.also { it.start() }
        }
    }

    private fun connectLoop(address: String) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            var backoff = 1000L
            var failures = 0
            while (true) {
                _state.update { it.copy(connection = Connection.Connecting(address)) }
                val closed = kotlinx.coroutines.CompletableDeferred<String>()
                val token = _state.value.token
                val url = "ws://$address/ws" + if (token.isNotEmpty()) "?token=$token" else ""
                socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        backoff = 1000L
                        failures = 0
                        _state.update { it.copy(connection = Connection.Connected(address)) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete("연결 종료")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(
                            if (response?.code == 401) "토큰이 맞지 않음" else (t.message ?: "연결 실패")
                        )
                    }
                })
                val reason = closed.await()
                _state.update { it.copy(connection = Connection.Failed(address, reason)) }
                failures++
                if (failures >= 4 && _state.value.manualAddress.isBlank()) {
                    // PC의 IP가 바뀌었을 수 있으니 다시 찾습니다.
                    delay(backoff)
                    start()
                    return@launch
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "usage" -> {
                val providers = parseProviders(msg.optJSONArray("providers") ?: JSONArray())
                val offset = msg.optDouble("server_time", now()) - now()
                _state.update { it.copy(providers = providers, clockOffset = offset) }
                prefs.edit()
                    .putString("usage_cache", msg.optJSONArray("providers")?.toString())
                    .putFloat("clock_offset", offset.toFloat())
                    .apply()
            }
            "media" -> _state.update { it.copy(media = parseMedia(msg)) }
            "thumbnail" -> {
                val img = msg.optJSONObject("image")
                val bmp = img?.optString("b64")?.takeIf { it.isNotEmpty() }?.let {
                    runCatching {
                        val bytes = Base64.decode(it, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                _state.update { it.copy(thumbnail = bmp) }
            }
        }
    }

    private fun loadCachedProviders(): List<Provider> {
        val raw = prefs.getString("usage_cache", null) ?: return emptyList()
        return runCatching {
            // 캐시에서 읽은 값은 PC와 연결되기 전까지 '마지막 값'으로 표시
            parseProviders(JSONArray(raw)).map { p ->
                if (p.windows.isNotEmpty() && p.status == "ok") p.copy(status = "stale") else p
            }
        }.getOrDefault(emptyList())
    }

    // ---------------- 명령 ----------------

    private fun send(obj: JSONObject) {
        socket?.send(obj.toString())
    }

    fun media(cmd: String, value: Double? = null) = send(JSONObject().apply {
        put("type", "media_cmd")
        put("cmd", cmd)
        if (value != null) put("value", value)
    })

    fun refresh() = send(JSONObject().put("type", "refresh"))

    fun saveSettings(address: String, token: String) {
        prefs.edit().putString("manual_address", address.trim()).putString("token", token.trim()).apply()
        _state.update { it.copy(manualAddress = address.trim(), token = token.trim()) }
        start()
    }

    override fun onCleared() {
        connectJob?.cancel()
        socket?.cancel()
        discovery?.stop()
    }

    companion object {
        fun now() = System.currentTimeMillis() / 1000.0
    }
}

/** mDNS(_aidock._tcp)로 같은 네트워크의 컴패니언을 찾습니다. */
private class Discovery(
    private val context: Context,
    private val onFound: (host: String, port: Int) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var lock: WifiManager.MulticastLock? = null
    private var found = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery start failed $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (found) return
            @Suppress("DEPRECATION")
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress ?: return
                    if (found) return
                    found = true
                    onFound(host, info.port)
                    stop()
                }
            })
        }
    }

    fun start() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("aidock").apply { setReferenceCounted(false); acquire() }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        runCatching { nsd.stopServiceDiscovery(listener) }
        runCatching { lock?.release() }
    }

    companion object {
        const val TAG = "AIDock"
        const val SERVICE_TYPE = "_aidock._tcp."
    }
}
