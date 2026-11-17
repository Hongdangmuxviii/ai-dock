package com.aidock.dock

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
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
    /** 배경: "pc"(PC가 보낸 이미지) | "device"(기기에서 고른 사진) | "default"(기본 그라데이션) */
    val bgMode: String = "pc",
    /** 배경 이미지 위에 덮는 어두운 막의 불투명도 (글자 가독성) */
    val bgDim: Float = 0.35f,
    val pcBackground: ImageBitmap? = null,
    val deviceBackground: ImageBitmap? = null,
) {
    val background: ImageBitmap?
        get() = when (bgMode) {
            "device" -> deviceBackground
            "pc" -> pcBackground
            else -> null
        }
}

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
            bgMode = prefs.getString("bg_mode", "pc") ?: "pc",
            bgDim = prefs.getFloat("bg_dim", 0.35f),
        )
    )

    private val pcBgFile = File(app.filesDir, "bg_pc.img")
    private val deviceBgFile = File(app.filesDir, "bg_device.img")
    val state: StateFlow<DockState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var discovery: Discovery? = null

    /** PC 컴패니언이 이 기기로 먼저 접속해 온 연결 (역방향). 있으면 이쪽을 우선 씁니다. */
    @Volatile private var inboundConn: org.java_websocket.WebSocket? = null
    private var inbound: InboundServer? = null

    init {
        // 저장해둔 배경을 먼저 띄워서 PC가 꺼져 있어도 배경이 유지되게
        viewModelScope.launch(Dispatchers.IO) {
            val pc = decodeFile(pcBgFile)
            val dev = decodeFile(deviceBgFile)
            _state.update { it.copy(pcBackground = pc, deviceBackground = dev) }
        }
        startInbound()
        start()
    }

    // ---------------- 배경 ----------------

    fun setBackgroundMode(mode: String) {
        prefs.edit().putString("bg_mode", mode).apply()
        _state.update { it.copy(bgMode = mode) }
    }

    fun setBackgroundDim(dim: Float) {
        prefs.edit().putFloat("bg_dim", dim).apply()
        _state.update { it.copy(bgDim = dim) }
    }

    /** 사진 선택기에서 고른 이미지를 화면 크기에 맞게 줄여 저장하고 배경으로 씁니다. */
    fun setDeviceBackground(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val bmp = decodeSampled(bytes) ?: return@launch
            runCatching {
                deviceBgFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            prefs.edit().putString("bg_mode", "device").apply()
            _state.update { it.copy(deviceBackground = bmp.asImageBitmap(), bgMode = "device") }
        }
    }

    private fun onPcBackground(img: JSONObject?) {
        if (img == null) {
            pcBgFile.delete()
            prefs.edit().remove("bg_pc_id").apply()
            _state.update { it.copy(pcBackground = null) }
            return
        }
        val id = img.optString("id")
        if (id.isNotEmpty() && id == prefs.getString("bg_pc_id", null) &&
            pcBgFile.exists() && _state.value.pcBackground != null
        ) return // 이미 받은 이미지
        val bytes = runCatching { Base64.decode(img.optString("b64"), Base64.DEFAULT) }.getOrNull() ?: return
        val bmp = decodeSampled(bytes) ?: return
        runCatching { pcBgFile.writeBytes(bytes) }
        prefs.edit().putString("bg_pc_id", id).apply()
        _state.update { it.copy(pcBackground = bmp.asImageBitmap()) }
    }

    private fun decodeFile(f: File): ImageBitmap? =
        if (!f.exists()) null else runCatching { decodeSampled(f.readBytes())?.asImageBitmap() }.getOrNull()

    /** 긴 변이 MAX_BG_PX 정도가 되도록 줄여서 디코드 (큰 사진으로 메모리가 터지지 않게) */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_BG_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------- 역방향 연결 (PC → 기기) ----------------

    private fun startInbound() {
        inbound = runCatching {
            InboundServer(INBOUND_PORT, { _state.value.token }, object : InboundServer.Listener {
                override fun onOpen(conn: org.java_websocket.WebSocket, peer: String) {
                    val old = inboundConn
                    inboundConn = conn
                    if (old != null && old !== conn) old.close()
                    // 기기→PC 시도는 멈춤
                    connectJob?.cancel()
                    socket?.cancel()
                    discovery?.stop()
                    _state.update { it.copy(connection = Connection.Connected(peer)) }
                }

                override fun onMessage(conn: org.java_websocket.WebSocket, text: String) {
                    if (conn === inboundConn) handle(text)
                }

                override fun onClose(conn: org.java_websocket.WebSocket) {
                    if (conn !== inboundConn) return
                    inboundConn = null
                    viewModelScope.launch { start() }
                }
            }).also { it.start() }
        }.onFailure { Log.w(TAG, "inbound server failed", it) }.getOrNull()
    }

    // ---------------- 연결 (기기 → PC) ----------------

    private fun start() {
        if (inboundConn?.isOpen == true) return
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
        if (connectJob?.isActive == true || inboundConn?.isOpen == true) return
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
                        if (inboundConn?.isOpen == true) {
                            webSocket.close(1000, "inbound active")
                            return
                        }
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
            "background" -> onPcBackground(msg.optJSONObject("image"))
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
        val text = obj.toString()
        val inb = inboundConn
        if (inb != null && inb.isOpen) {
            // 웹소켓 서버 스레드와 겹치지 않게 IO 스레드에서 보냄
            viewModelScope.launch(Dispatchers.IO) { runCatching { inb.send(text) } }
        } else {
            socket?.send(text)
        }
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
        runCatching { inbound?.stop(1000) }
    }

    companion object {
        const val TAG = "AIDock"
        /** PC가 이 기기로 접속할 때 쓰는 포트 (컴패니언 config 의 device_port 와 같아야 함) */
        const val INBOUND_PORT = 8766
        const val MAX_BG_PX = 1600
        fun now() = System.currentTimeMillis() / 1000.0
    }
}

/** PC 컴패니언의 접속을 기다리는 웹소켓 서버. 주고받는 메시지는 기기→PC 연결과 같습니다. */
private class InboundServer(
    port: Int,
    private val token: () -> String,
    private val listener: Listener,
) : WebSocketServer(InetSocketAddress(port)) {
    interface Listener {
        fun onOpen(conn: org.java_websocket.WebSocket, peer: String)
        fun onMessage(conn: org.java_websocket.WebSocket, text: String)
        fun onClose(conn: org.java_websocket.WebSocket)
    }

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: org.java_websocket.WebSocket, handshake: ClientHandshake) {
        val expected = token()
        if (expected.isNotEmpty()) {
            val given = Uri.parse("http://x" + handshake.resourceDescriptor).getQueryParameter("token")
            if (given != expected) {
                conn.close(1008, "bad token")
                return
            }
        }
        val peer = conn.remoteSocketAddress?.address?.hostAddress ?: "PC"
        Log.i(DockViewModel.TAG, "PC connected from $peer")
        listener.onOpen(conn, peer)
    }

    override fun onMessage(conn: org.java_websocket.WebSocket, message: String) =
        listener.onMessage(conn, message)

    override fun onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String?, remote: Boolean) =
        listener.onClose(conn)

    override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception) {
        Log.w(DockViewModel.TAG, "inbound error", ex)
    }

    override fun onStart() {
        Log.i(DockViewModel.TAG, "waiting for PC on port $port")
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
