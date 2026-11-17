package com.aidock.dock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidock.dock.Connection
import com.aidock.dock.DockState
import com.aidock.dock.DockViewModel
import com.aidock.dock.Media
import com.aidock.dock.Provider
import com.aidock.dock.UsageWindow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val SecFmt = DateTimeFormatter.ofPattern("ss")
private val DateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

@Composable
fun DockScreen(vm: DockViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var now by remember { mutableDoubleStateOf(DockViewModel.now()) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = DockViewModel.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DockColors.Background)
            .padding(20.dp)
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ClockPanel(now, state.connection, compact = true) { showSettings = true }
                    Spacer(Modifier.height(16.dp))
                    UsagePanel(state, now, Modifier.weight(1f), onRefresh = vm::refresh)
                }
                MediaPanel(state, now, vm, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ClockPanel(now, state.connection, compact = false) { showSettings = true }
                Spacer(Modifier.height(20.dp))
                UsagePanel(state, now, Modifier.weight(1f), onRefresh = vm::refresh)
                Spacer(Modifier.height(16.dp))
                MediaPanel(state, now, vm, Modifier.fillMaxWidth())
            }
        }
    }

    if (showSettings) {
        SettingsDialog(state, onDismiss = { showSettings = false }) { addr, token ->
            vm.saveSettings(addr, token)
            showSettings = false
        }
    }
}

// ---------------------------------------------------------------- 시계

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClockPanel(now: Double, conn: Connection, compact: Boolean, onLongPress: () -> Unit) {
    val t = Instant.ofEpochMilli((now * 1000).toLong()).atZone(ZoneId.systemDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                t.format(TimeFmt),
                fontSize = if (compact) 72.sp else 96.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                color = DockColors.Text,
                lineHeight = if (compact) 72.sp else 96.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t.format(SecFmt),
                fontSize = if (compact) 24.sp else 30.sp,
                color = DockColors.TextDim,
                modifier = Modifier.padding(bottom = if (compact) 10.dp else 14.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.format(DateFmt), fontSize = 20.sp, color = DockColors.TextDim)
            Spacer(Modifier.weight(1f))
            ConnectionBadge(conn)
        }
    }
}

@Composable
private fun ConnectionBadge(conn: Connection) {
    val (color, text) = when (conn) {
        is Connection.Connected -> DockColors.Ok to "PC 연결됨"
        is Connection.Connecting -> DockColors.Warn to "연결 중…"
        is Connection.Searching -> DockColors.Warn to "PC 찾는 중…"
        is Connection.Failed -> DockColors.Danger to conn.reason
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, color = DockColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- 사용량

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsagePanel(state: DockState, now: Double, modifier: Modifier, onRefresh: () -> Unit) {
    val pcNow = now + state.clockOffset
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.providers.isEmpty()) {
            Card {
                Text("PC 컴패니언에 연결되면 사용량이 표시됩니다.", color = DockColors.TextDim, fontSize = 15.sp)
            }
        }
        state.providers.forEach { p ->
            Card(Modifier.combinedClickable(onClick = {}, onLongClick = onRefresh)) {
                ProviderCard(p, pcNow)
            }
        }
    }
}

@Composable
private fun ProviderCard(p: Provider, pcNow: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(DockColors.provider(p.id)))
        Spacer(Modifier.width(8.dp))
        Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DockColors.Text)
        Spacer(Modifier.weight(1f))
        Text(statusText(p, pcNow), fontSize = 12.sp, color = statusColor(p.status))
    }
    if (p.windows.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            p.message.ifEmpty { "데이터 없음" },
            fontSize = 13.sp, color = DockColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        return
    }
    p.windows.take(4).forEach { w ->
        Spacer(Modifier.height(10.dp))
        WindowRow(p.id, w, pcNow)
    }
}

@Composable
private fun WindowRow(providerId: String, w: UsageWindow, pcNow: Double) {
    // 초기화 시각이 지났으면 PC와 연결이 끊겨 있어도 0%로 간주
    val reset = w.resetsAt != null && w.resetsAt <= pcNow
    val pct = if (reset) 0.0 else w.usedPercent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(w.label, fontSize = 14.sp, color = DockColors.TextDim, modifier = Modifier.width(96.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(DockColors.Track)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                    .background(DockColors.usage(providerId, pct))
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("${pct.roundToInt()}%", fontSize = 16.sp, fontWeight = FontWeight.Medium,
            color = DockColors.Text, modifier = Modifier.width(48.dp))
    }
    val sub = when {
        reset -> "초기화됨"
        w.resetsAt != null -> "${countdown(w.resetsAt - pcNow)} 후 초기화"
        else -> ""
    }
    if (sub.isNotEmpty()) {
        Text(sub, fontSize = 12.sp, color = DockColors.TextDim, modifier = Modifier.padding(start = 96.dp, top = 2.dp))
    }
}

private fun countdown(sec: Double): String {
    val s = max(0.0, sec).toLong()
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> "${d}일 ${h}시간"
        h > 0 -> "${h}시간 ${m}분"
        else -> "${max(1L, m)}분"
    }
}

private fun statusText(p: Provider, pcNow: Double): String {
    val ago = p.updatedAt?.let { pcNow - it }
    val agoText = when {
        ago == null -> ""
        ago < 90 -> "방금"
        ago < 3600 -> "${(ago / 60).toInt()}분 전"
        ago < 86400 -> "${(ago / 3600).toInt()}시간 전"
        else -> "${(ago / 86400).toInt()}일 전"
    }
    return when (p.status) {
        "ok" -> agoText
        "stale" -> "마지막 값 · $agoText"
        "error" -> "오류"
        "unavailable" -> "사용 불가"
        else -> ""
    }
}

private fun statusColor(status: String) = when (status) {
    "ok" -> DockColors.TextDim
    "stale" -> DockColors.Warn
    "error", "unavailable" -> DockColors.Danger
    else -> DockColors.TextDim
}

// ---------------------------------------------------------------- 미디어

@Composable
private fun MediaPanel(state: DockState, now: Double, vm: DockViewModel, modifier: Modifier) {
    val m = state.media
    Card(modifier) {
        if (state.connection !is Connection.Connected) {
            Text("PC와 연결되면 리모컨을 쓸 수 있습니다.", color = DockColors.TextDim, fontSize = 15.sp)
            return@Card
        }
        if (!m.available) {
            Text(m.reason.ifEmpty { "미디어 제어를 쓸 수 없음" }, color = DockColors.TextDim, fontSize = 15.sp)
            return@Card
        }
        if (!m.active) {
            Text("재생 중인 미디어 없음", color = DockColors.TextDim, fontSize = 15.sp)
            return@Card
        }
        MediaContent(m, state, now, vm)
    }
}

@Composable
private fun ColumnScope.MediaContent(m: Media, state: DockState, now: Double, vm: DockViewModel) {
    val thumb = state.thumbnail
    if (thumb != null) {
        Image(
            thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(12.dp))
    }
    Text(m.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DockColors.Text,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (m.artist.isNotEmpty()) {
        Text(m.artist, fontSize = 14.sp, color = DockColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))

    // 재생 중이면 PC가 마지막으로 알려준 위치에서 흐른 시간만큼 앞으로 계산
    val pcNow = now + state.clockOffset
    val live = if (m.playing) m.position + (pcNow - m.positionAt) else m.position
    val pos = live.coerceIn(0.0, max(m.duration, 0.0))
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    if (m.duration > 0) {
        Slider(
            value = if (dragging) dragValue else pos.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { vm.media("seek", dragValue.toDouble()); dragging = false },
            valueRange = 0f..m.duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = DockColors.Text,
                activeTrackColor = DockColors.Text,
                inactiveTrackColor = DockColors.Track,
            ),
        )
        Row {
            Text(mmss(if (dragging) dragValue.toDouble() else pos), fontSize = 12.sp, color = DockColors.TextDim)
            Spacer(Modifier.weight(1f))
            Text(mmss(m.duration), fontSize = 12.sp, color = DockColors.TextDim)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallButton(Icons.Filled.VolumeOff) { vm.media("mute") }
        SmallButton(Icons.Filled.VolumeDown) { vm.media("vol_down") }
        SmallButton(Icons.Filled.SkipPrevious, 52.dp) { vm.media("prev") }
        FilledIconButton(
            onClick = { vm.media("toggle") },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = DockColors.Text, contentColor = DockColors.Background,
            ),
        ) {
            Icon(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(40.dp))
        }
        SmallButton(Icons.Filled.SkipNext, 52.dp) { vm.media("next") }
        SmallButton(Icons.Filled.VolumeUp) { vm.media("vol_up") }
    }
}

@Composable
private fun SmallButton(icon: ImageVector, size: Dp = 44.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(icon, null, tint = DockColors.Text, modifier = Modifier.size(size * 0.6f))
    }
}

private fun mmss(sec: Double): String {
    val s = max(0.0, sec).toInt()
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- 공통

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DockColors.Surface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SettingsDialog(
    state: DockState,
    onDismiss: () -> Unit,
    onSave: (address: String, token: String) -> Unit,
) {
    var address by remember { mutableStateOf(state.manualAddress) }
    var token by remember { mutableStateOf(state.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연결 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "PC 주소를 비워두면 같은 네트워크에서 자동으로 찾습니다.",
                    fontSize = 13.sp, color = DockColors.TextDim,
                )
                OutlinedTextField(
                    address, { address = it }, singleLine = true,
                    label = { Text("PC 주소 (예: 192.168.0.10:8765)") },
                )
                OutlinedTextField(
                    token, { token = it }, singleLine = true,
                    label = { Text("토큰 (컴패니언 config.json의 token)") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(address, token) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
