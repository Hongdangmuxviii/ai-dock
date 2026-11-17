package com.aidock.dock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
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

/**
 * 작은 세로 화면(토스프론트2: 800x1280 @320dpi = 400x640dp)용 촘촘한 배치.
 * 썸네일을 작게, 카드 여백을 줄여서 사용량이 스크롤 없이 보이게 합니다.
 */
private val LocalDense = staticCompositionLocalOf { false }

/** 화면 전체 배경. 유리 카드가 자기 위치의 배경을 흐리게 다시 그릴 때 씁니다. */
private class Backdrop(val image: ImageBitmap?, val dim: Float, val rootSize: Size)

private val LocalBackdrop = compositionLocalOf { Backdrop(null, 0f, Size.Zero) }

/** 배경을 그립니다. offset 은 이 그림이 화면 왼쪽 위에서 얼마나 떨어져 있는지의 음수. */
private fun DrawScope.drawBackdrop(b: Backdrop, offset: Offset) {
    val w = b.rootSize.width
    val h = b.rootSize.height
    if (w <= 0f || h <= 0f) return
    translate(offset.x, offset.y) {
        val img = b.image
        if (img != null) {
            // ContentScale.Crop 과 같은 방식으로 화면을 꽉 채움
            val scale = max(w / img.width, h / img.height)
            val dw = img.width * scale
            val dh = img.height * scale
            drawImage(
                img,
                dstOffset = IntOffset(((w - dw) / 2).roundToInt(), ((h - dh) / 2).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            drawRect(Color.Black.copy(alpha = b.dim), size = Size(w, h))
        } else {
            // 기본 배경: 짙은 남색 위에 서비스 색의 은은한 빛
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF0C1028), Color(0xFF140F2E), Color(0xFF06060B))),
                size = Size(w, h),
            )
            fun glow(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy),
            )
            glow(DockColors.Claude.copy(alpha = 0.55f), w * 0.92f, h * 0.20f, w * 0.75f)
            glow(DockColors.Codex.copy(alpha = 0.45f), w * 0.05f, h * 0.55f, w * 0.80f)
            glow(DockColors.Gemini.copy(alpha = 0.45f), w * 0.75f, h * 0.95f, w * 0.75f)
        }
    }
}

/** 배경 이미지 위에 바로 놓이는 시계 글자가 묻히지 않게 */
private val TextShadowStyle = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 18f))

private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val SecFmt = DateTimeFormatter.ofPattern("ss")
private val DateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

@Composable
fun DockScreen(
    vm: DockViewModel,
    onPickImage: () -> Unit,
    onNightMode: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var now by remember { mutableDoubleStateOf(DockViewModel.now()) }
    /** 열려 있는 설정 창: null | "background" | "connection" */
    var dialog by remember { mutableStateOf<String?>(null) }
    var night by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = DockViewModel.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    // 화면 밝기는 액티비티 창 설정이라 바깥(MainActivity)에서 바꿉니다.
    LaunchedEffect(night) { onNightMode(night) }

    if (night) {
        BackHandler { night = false }
        NightClock(now) { night = false }
        return
    }

    val handleMenu: (String) -> Unit = { key ->
        when (key) {
            "night" -> night = true
            "exit" -> onExit()
            else -> dialog = key
        }
    }

    val config = LocalConfiguration.current
    val smallScreen = minOf(config.screenWidthDp, config.screenHeightDp) < 480
    var rootSize by remember { mutableStateOf(Size.Zero) }
    val backdrop = Backdrop(state.background, state.bgDim, rootSize)
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DockColors.Background)
            .onSizeChanged { rootSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .drawBehind { drawBackdrop(backdrop, Offset.Zero) }
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(if (smallScreen) 16.dp else 20.dp)
    ) {
        val landscape = maxWidth > maxHeight
        val dense = if (landscape) maxHeight < 440.dp else maxHeight < 760.dp
        CompositionLocalProvider(LocalDense provides dense) {
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ClockPanel(now, state.connection, compact = true,
                        onMenu = handleMenu, onRefresh = vm::refresh)
                    Spacer(Modifier.height(16.dp))
                    UsagePanel(state, now, Modifier.weight(1f), onRefresh = vm::refresh)
                }
                MediaPanel(state, now, vm, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ClockPanel(now, state.connection, compact = dense,
                    onMenu = handleMenu, onRefresh = vm::refresh)
                Spacer(Modifier.height(if (dense) 12.dp else 20.dp))
                UsagePanel(state, now, Modifier.weight(1f), onRefresh = vm::refresh)
                Spacer(Modifier.height(if (dense) 12.dp else 16.dp))
                MediaPanel(state, now, vm, Modifier.fillMaxWidth())
            }
        }
        }
    }

    }

    when (dialog) {
        "background" -> BackgroundDialog(
            state,
            onDismiss = { dialog = null },
            onBackgroundMode = vm::setBackgroundMode,
            onDim = vm::setBackgroundDim,
            onPickImage = { dialog = null; onPickImage() },
        )
        "connection" -> ConnectionDialog(state, onDismiss = { dialog = null }) { addr, token ->
            vm.saveSettings(addr, token)
            dialog = null
        }
    }
}

// ---------------------------------------------------------------- 취침 모드

/**
 * AOD처럼 최소한으로만 보이는 시계.
 * - 완전히 검은 배경에 어두운 회색 글자 (밝기 최저와 함께 쓰면 어두운 방에서도 눈부시지 않음)
 * - 1분마다 위치를 조금씩 옮겨 같은 자리에 자국이 남지 않게 (번인 방지)
 * - 화면 아무 데나 탭하거나 뒤로가기를 누르면 원래 화면으로
 */
@Composable
private fun NightClock(now: Double, onExit: () -> Unit) {
    val t = Instant.ofEpochMilli((now * 1000).toLong()).atZone(ZoneId.systemDefault())
    val minute = (now / 60).toLong()
    // 3x3 격자 위치를 분마다 돌아가며 사용 (가운데 기준 ±24dp)
    val step = (minute % 9).toInt()
    val dx = ((step % 3) - 1) * 24
    val dy = ((step / 3) - 1) * 24
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onExit() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(dx.dp, dy.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                t.format(TimeFmt),
                fontSize = 84.sp,
                fontWeight = FontWeight.Thin,
                color = DockColors.NightText,
                lineHeight = 84.sp,
            )
            Text(t.format(DateFmt), fontSize = 16.sp, color = DockColors.NightTextDim)
        }
    }
}

// ---------------------------------------------------------------- 시계

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClockPanel(
    now: Double,
    conn: Connection,
    compact: Boolean,
    onMenu: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val t = Instant.ofEpochMilli((now * 1000).toLong()).atZone(ZoneId.systemDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onMenu("connection") })
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                t.format(TimeFmt),
                fontSize = if (compact) 72.sp else 96.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif,
                color = DockColors.Text,
                lineHeight = if (compact) 72.sp else 96.sp,
                style = TextShadowStyle,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t.format(SecFmt),
                fontSize = if (compact) 24.sp else 30.sp,
                color = DockColors.TextDim,
                modifier = Modifier.padding(bottom = if (compact) 10.dp else 14.dp),
            )
            Spacer(Modifier.weight(1f))
            DockMenu(Modifier.align(Alignment.Top), onMenu, onRefresh)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.format(DateFmt), fontSize = 20.sp, color = DockColors.TextDim, style = TextShadowStyle)
            Spacer(Modifier.weight(1f))
            ConnectionBadge(conn)
        }
    }
}

/** 우측 상단 메뉴 버튼(⋮). 누르면 설정 항목이 펼쳐집니다. */
@Composable
private fun DockMenu(modifier: Modifier, onMenu: (String) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 취침 모드: 검은 화면에 시계만, 밝기 최저
            IconButton(onClick = { onMenu("night") }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Bedtime, contentDescription = "취침 모드", tint = DockColors.Text,
                    modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "메뉴", tint = DockColors.Text,
                    modifier = Modifier.size(28.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(DockColors.SurfaceHigh),
        ) {
            @Composable
            fun item(icon: ImageVector, label: String, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label, color = DockColors.Text, fontSize = 16.sp) },
                leadingIcon = { Icon(icon, null, tint = DockColors.TextDim) },
                onClick = { open = false; action() },
            )
            item(Icons.Filled.Image, "배경화면") { onMenu("background") }
            item(Icons.Filled.Wifi, "PC 연결") { onMenu("connection") }
            item(Icons.Filled.Refresh, "사용량 새로고침", onRefresh)
            item(Icons.Filled.Bedtime, "취침 모드") { onMenu("night") }
            item(Icons.Filled.PowerSettingsNew, "앱 종료") { onMenu("exit") }
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
    val dense = LocalDense.current
    // 사용량은 카드 없이 배경 위에 바로 얹습니다(시계처럼). 밝은 배경에서도 읽히게 글자에 그림자.
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextShadowStyle)) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.providers.isEmpty()) {
                Text("PC 컴패니언에 연결되면 사용량이 표시됩니다.", color = DockColors.TextDim, fontSize = 15.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            state.providers.forEachIndexed { i, p ->
                if (i > 0) {
                    // 도구 사이 아주 옅은 구분선
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DockColors.Divider))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = onRefresh)
                        .padding(vertical = if (dense) 12.dp else 16.dp)
                ) {
                    ProviderCard(p, pcNow)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(p: Provider, pcNow: Double) {
    val dense = LocalDense.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(DockColors.provider(p.id)))
        Spacer(Modifier.width(8.dp))
        Text(p.name, fontSize = if (dense) 16.sp else 18.sp, fontWeight = FontWeight.SemiBold, color = DockColors.Text)
        Spacer(Modifier.weight(1f))
        Text(statusText(p, pcNow), fontSize = 12.sp, color = statusColor(p.status))
    }
    if (p.windows.isEmpty()) {
        Spacer(Modifier.height(if (dense) 2.dp else 6.dp))
        Text(
            p.message.ifEmpty { "데이터 없음" },
            fontSize = if (dense) 12.sp else 13.sp, color = DockColors.TextDim,
            maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis,
        )
        return
    }
    p.windows.take(4).forEach { w ->
        Spacer(Modifier.height(if (dense) 8.dp else 10.dp))
        WindowRow(p.id, w, pcNow)
    }
}

@Composable
private fun WindowRow(providerId: String, w: UsageWindow, pcNow: Double) {
    // 초기화 시각이 지났으면 PC와 연결이 끊겨 있어도 0%로 간주
    val reset = w.resetsAt != null && w.resetsAt <= pcNow
    val pct = if (reset) 0.0 else w.usedPercent
    val dense = LocalDense.current
    val sub = when {
        reset -> "초기화됨"
        w.resetsAt != null -> "${countdown(w.resetsAt - pcNow)} 후 초기화"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(w.label, fontSize = 14.sp, color = DockColors.TextDim,
            modifier = Modifier.width(if (dense) 64.dp else 96.dp),
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
            color = DockColors.Text, modifier = Modifier.width(if (dense) 42.dp else 48.dp))
        if (dense) {
            // 좁은 화면에서는 초기화 시각을 같은 줄 오른쪽에 붙여 한 줄로 끝냅니다.
            Text(sub.removeSuffix(" 후 초기화"), fontSize = 12.sp, color = DockColors.TextDim,
                modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End)
        }
    }
    if (sub.isNotEmpty() && !dense) {
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
    Card(modifier, glass = true) {
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
    val dense = LocalDense.current
    if (dense) {
        // 작은 화면: 썸네일을 왼쪽에 작게, 제목은 오른쪽에
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) {
                Image(
                    thumb, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(m.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DockColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                if (m.artist.isNotEmpty()) {
                    Text(m.artist, fontSize = 13.sp, color = DockColors.TextDim, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
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
    }
    Spacer(Modifier.height(if (dense) 4.dp else 8.dp))

    // 재생 중이면 PC가 마지막으로 알려준 위치에서 흐른 시간만큼 앞으로 계산
    val pcNow = now + state.clockOffset
    val live = if (m.playing) m.position + (pcNow - m.positionAt) else m.position
    val pos = live.coerceIn(0.0, max(m.duration, 0.0))
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 재생바는 항상 자리를 차지합니다. 길이를 모를 때(0)는 비활성으로만 바꿔서 화면이 들썩이지 않게.
    val known = m.duration > 0
    val shown = if (dragging) dragValue.toDouble() else pos
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (known) mmss(shown) else "--:--", fontSize = 12.sp, color = DockColors.TextDim,
            modifier = Modifier.width(44.dp))
        SeekBar(
            fraction = if (known) (shown / m.duration).toFloat() else 0f,
            enabled = known,
            onDrag = { f -> dragging = true; dragValue = (f * m.duration).toFloat() },
            onRelease = { f -> vm.media("seek", f * m.duration); dragging = false },
            modifier = Modifier.weight(1f),
        )
        Text(if (known) mmss(m.duration) else "--:--", fontSize = 12.sp, color = DockColors.TextDim,
            modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
    Spacer(Modifier.height(if (dense) 4.dp else 8.dp))
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
            modifier = Modifier.size(if (dense) 60.dp else 72.dp),
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

/** 얇은 재생바. 탭하거나 끌어서 위치를 옮기고, 손을 떼면 onRelease 로 최종 위치(0~1)를 알립니다. */
@Composable
private fun SeekBar(
    fraction: Float,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableFloatStateOf(1f) }
    var last by remember { mutableFloatStateOf(0f) }
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    val input = if (!enabled) Modifier else Modifier
        .pointerInput(Unit) {
            detectTapGestures { o -> onRelease(at(o.x)) }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o -> last = at(o.x); onDrag(last) },
                onHorizontalDrag = { change, _ -> last = at(change.position.x); onDrag(last) },
                onDragEnd = { onRelease(last) },
                onDragCancel = { onRelease(last) },
            )
        }
    Canvas(
        modifier
            .height(24.dp)
            .padding(horizontal = 6.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .then(input)
    ) {
        val y = size.height / 2
        val track = 4.dp.toPx()
        val f = fraction.coerceIn(0f, 1f)
        drawLine(DockColors.Track, Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
        if (enabled) {
            drawLine(DockColors.Text, Offset(0f, y), Offset(size.width * f, y), track, StrokeCap.Round)
            drawCircle(DockColors.Text, 6.dp.toPx(), Offset(size.width * f, y))
        }
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

/**
 * 카드. glass=true 면 글래스모피즘(카드 자리의 배경을 흐리게 다시 그리고 반투명 막과 빛나는 테두리),
 * false 면 불투명한 단색 카드입니다. 지금은 유튜브 리모컨만 유리 카드이고, 사용량은 카드 없이 배경 위에 바로 그립니다.
 * (흐림은 안드로이드 12 이상에서만 적용되고, 그 아래에서는 반투명 막만 보입니다)
 */
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dense = LocalDense.current
    val backdrop = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(if (dense) 20.dp else 24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.onGloballyPositioned { pos = it.positionInRoot() } else Modifier)
            .clip(shape)
    ) {
        if (glass) {
            Canvas(Modifier.matchParentSize().blur(28.dp, BlurredEdgeTreatment.Rectangle)) {
                drawBackdrop(backdrop, -pos)
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(DockColors.GlassTop, DockColors.GlassBottom)))
                    .border(1.dp, Brush.verticalGradient(listOf(DockColors.GlassEdgeTop, DockColors.GlassEdgeBottom)), shape)
            )
        } else {
            Box(Modifier.matchParentSize().background(DockColors.Surface))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(if (dense) 14.dp else 18.dp),
            content = content,
        )
    }
}

@Composable
private fun BackgroundDialog(
    state: DockState,
    onDismiss: () -> Unit,
    onBackgroundMode: (String) -> Unit,
    onDim: (Float) -> Unit,
    onPickImage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("배경화면") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("pc" to "PC 이미지", "device" to "기기 사진", "default" to "기본").forEach { (mode, label) ->
                        FilterChip(
                            selected = state.bgMode == mode,
                            onClick = { onBackgroundMode(mode) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    when (state.bgMode) {
                        "pc" -> if (state.pcBackground == null)
                            "PC의 %USERPROFILE%\\.aidock\\background.jpg 를 두면 자동으로 받아옵니다. (png/webp도 가능)"
                        else "PC에서 받은 이미지를 쓰는 중. PC의 파일을 바꾸면 몇 초 안에 반영됩니다."
                        "device" -> if (state.deviceBackground == null) "아래 버튼으로 사진을 고르세요." else "기기에서 고른 사진을 쓰는 중."
                        else -> "기본 그라데이션 배경."
                    },
                    fontSize = 12.sp, color = DockColors.TextDim,
                )
                OutlinedButton(onClick = onPickImage) { Text("기기에서 사진 고르기") }
                Text("배경 어둡게: ${(state.bgDim * 100).roundToInt()}%", fontSize = 13.sp, color = DockColors.TextDim)
                Slider(value = state.bgDim, onValueChange = onDim, valueRange = 0f..0.8f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun ConnectionDialog(
    state: DockState,
    onDismiss: () -> Unit,
    onSave: (address: String, token: String) -> Unit,
) {
    var address by remember { mutableStateOf(state.manualAddress) }
    var token by remember { mutableStateOf(state.token) }
    val conn = when (val c = state.connection) {
        is Connection.Connected -> "연결됨: ${c.address}"
        is Connection.Connecting -> "연결 중: ${c.address}"
        is Connection.Searching -> "PC를 찾는 중"
        is Connection.Failed -> "실패: ${c.reason}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC 연결") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(conn, fontSize = 13.sp, color = DockColors.TextDim)
                Text(
                    "PC 주소를 비워두면 같은 네트워크에서 자동으로 찾습니다. " +
                        "PC가 이 기기로 접속하는 방식(컴패니언 device_address)이면 비워두세요.",
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
