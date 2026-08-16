package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.ChatPhase
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.camera.PhotoStore
import com.r1.launcher.chat.ChatMsg
import com.r1.launcher.chat.Role
import kotlinx.coroutines.delay

/**
 * One conversation.
 *
 *   wheel up / down     scroll the transcript
 *   side button HOLD    push-to-talk
 *   kbd                 on-screen keyboard for typed input
 *   img                 next send generates a picture instead of a reply
 *   clip                attach the newest photo as vision input
 *
 * Waiting states are the thing this panel is most careful about: `WAITING` is
 * the stretch where nothing is on screen yet, so it gets an explicit animated
 * row with an elapsed counter rather than an empty bubble.
 */
@Composable
fun ChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onToggleKb: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleImageMode: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onSpeakToggle: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.CHAT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Chat
        val listState = rememberLazyListState()
        val fontSize = state.chatTextSize

        // Rows = turns + (live assistant bubble) + (status row).
        val liveRow = state.chatStreaming.isNotBlank() || state.chatPhase == ChatPhase.WAITING ||
            state.chatPhase == ChatPhase.IMAGING
        val rowCount = state.chatMsgs.size + (if (liveRow) 1 else 0)

        // Follow the tail while the user hasn't scrolled away. Sticking to the
        // bottom during streaming is the whole reason this is keyed on the
        // streaming text as well as the message count.
        LaunchedEffect(rowCount, state.chatStreaming, state.chatScrollTick) {
            if (state.chatPinnedToBottom && rowCount > 0) {
                listState.animateScrollToItem((rowCount - 1).coerceAtLeast(0))
            }
        }
        // Wheel nudge. One notch moves about two thirds of a screen, which is
        // enough to feel like progress through a long answer without skipping
        // lines. Hitting the bottom re-arms autoscroll.
        LaunchedEffect(state.chatScrollSeq) {
            if (state.chatScrollSeq == 0) return@LaunchedEffect
            val h = listState.layoutInfo.viewportSize.height
            val step = (if (h > 0) h * 0.66f else 300f) * state.chatScrollDir
            listState.animateScrollBy(step)
        }

        // Detect a manual scroll away from the tail so we stop yanking them back.
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo }
                .collect { info ->
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                    val total = info.totalItemsCount
                    state.chatPinnedToBottom = total == 0 || last >= total - 1
                }
        }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            AppPageHeader(
                titleIconRes = R.drawable.ic_chat,
                title = state.chatTitle,
                backFocused = false,
                onBack = onBack,
                themeColor = accent,
                compact = true,
                gearFocused = false,
                onGear = onSettings,
                trailingContent = {
                    // Speaker toggle lives in the header because it changes the
                    // system prompt, so users flip it between turns.
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.chatSpeak) accent.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable(onClick = onSpeakToggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_sound),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (state.chatSpeak) accent else Color(0xFF5A5A5A)
                            ),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.chatMsgs.isEmpty() && !liveRow) {
                    EmptyChatHint(accent = accent)
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            items = state.chatMsgs,
                            key = { i, m -> "$i-${m.at}" },
                        ) { _, m ->
                            Bubble(msg = m, accent = accent, fontSize = fontSize)
                        }
                        if (liveRow) {
                            item(key = "live") {
                                if (state.chatStreaming.isNotBlank()) {
                                    Bubble(
                                        msg = ChatMsg(Role.ASSISTANT, state.chatStreaming, streaming = true),
                                        accent = accent,
                                        fontSize = fontSize,
                                    )
                                } else {
                                    PendingRow(state = state, accent = accent)
                                }
                            }
                        }
                    }
                }

                // Jump-to-latest, only while the user is scrolled away mid-reply.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !state.chatPinnedToBottom,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.85f))
                            .border(1.dp, accent, RoundedCornerShape(8.dp))
                            .clickable {
                                state.chatPinnedToBottom = true
                                state.chatScrollTick++
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▼", style = type.appCard.copy(fontSize = 15.sp), color = accent)
                    }
                }
            }

            Composer(
                state = state,
                accent = accent,
                onSend = onSend,
                onStop = onStop,
                onRetry = onRetry,
                onToggleKb = onToggleKb,
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onToggleImageMode = onToggleImageMode,
                onAttach = onAttach,
                onClearAttachment = onClearAttachment,
            )
        }
    }
}

@Composable
private fun EmptyChatHint(accent: Color) {
    val type = LocalR1Type.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_chat),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent.copy(alpha = 0.5f)),
            modifier = Modifier.size(34.dp),
        )
        Text(
            "hold the side button to talk",
            style = type.appCard.copy(fontSize = 15.sp),
            color = Color(0xFF7A7A7A),
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "or tap kbd to type",
            style = type.appCard.copy(fontSize = 14.sp),
            color = Color(0xFF5A5A5A),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * The gap between "sent" and "first token" — the only moment with nothing on
 * screen. Shows what it's doing plus seconds elapsed, so a slow model never
 * looks like a hang.
 */
@Composable
private fun PendingRow(state: LauncherState, accent: Color) {
    val type = LocalR1Type.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.chatPhase) {
        while (state.chatWorking) { now = System.currentTimeMillis(); delay(400) }
    }
    val secs = ((now - state.chatPhaseStartedAt).coerceAtLeast(0L)) / 1000
    val label = when (state.chatPhase) {
        ChatPhase.IMAGING -> "drawing"
        ChatPhase.TRANSCRIBING -> "hearing"
        else -> "thinking"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141414))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots(accent)
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 15.sp),
            color = accent,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (secs >= 2) {
            Text(
                text = "  ${secs}s",
                style = type.appCard.copy(fontSize = 13.sp),
                color = Color(0xFF6A6A6A),
            )
        }
    }
}

/** Three squares breathing in sequence — the retro take on a typing indicator. */
@Composable
private fun TypingDots(accent: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(520, delayMillis = i * 170, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(5.dp).background(accent.copy(alpha = a)))
        }
    }
}

@Composable
private fun Bubble(msg: ChatMsg, accent: Color, fontSize: Int) {
    val type = LocalR1Type.current
    val isUser = msg.role == Role.USER
    val style = type.appCard.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isUser) accent.copy(alpha = 0.16f) else Color(0xFF141414))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            msg.imagePath?.let { path ->
                val bmp = remember(path) {
                    PhotoStore.decode(PhotoStore.Photo(path, isAi = false, sizeBytes = 0), targetPx = 560)
                        ?.asImageBitmap()
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Text("[image missing]", style = style, color = Color(0xFF6A6A6A))
                }
                if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
            }

            if (msg.text.isNotBlank()) {
                if (isUser) {
                    // User turns are literal — never re-interpret their text as
                    // markup.
                    Text(text = msg.text, style = style, color = Color.White)
                } else {
                    MarkdownText(
                        text = msg.text,
                        style = style,
                        color = Color(0xFFE8E8E8),
                        accent = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: LauncherState,
    accent: Color,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onToggleKb: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onToggleImageMode: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
) {
    val type = LocalR1Type.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0B0B))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Recording: live level + partial transcript, so the user can see it is
        // hearing them before they let go.
        if (state.chatPhase == ChatPhase.RECORDING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFFFF3B30)))
                Text(
                    text = state.chatPartial.ifBlank { "listening…" },
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = if (state.chatPartial.isBlank()) Color(0xFFFF3B30) else Color.White,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF2A2A2A))) {
                Box(
                    Modifier
                        .fillMaxWidth((state.chatMicLevel / 100f).coerceIn(0.02f, 1f))
                        .height(3.dp)
                        .background(Color(0xFFFF3B30)),
                )
            }
        }

        if (state.chatPhase == ChatPhase.ERROR && state.chatError.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.chatError,
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = Color(0xFFFF6B4A),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Pill("retry", accent, onRetry)
            }
        }

        state.chatAttachment?.let { path ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = remember(path) {
                    PhotoStore.decode(PhotoStore.Photo(path, isAi = false, sizeBytes = 0), targetPx = 96)
                        ?.asImageBitmap()
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
                Text(
                    "photo attached",
                    style = type.appCard.copy(fontSize = 13.sp),
                    color = Color(0xFF9A9A9A),
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Pill("x", Color(0xFF9A9A9A), onClearAttachment)
            }
        }

        // Input line + actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.chatInput.ifBlank {
                    if (state.chatImageMode) "describe an image…" else "hold side button, or kbd"
                },
                style = type.appCard.copy(fontSize = 15.sp),
                color = if (state.chatInput.isBlank()) Color(0xFF5A5A5A) else Color.White,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
            if (state.chatWorking) {
                Pill("stop", Color(0xFFFF6B4A), onStop)
            } else {
                Pill(
                    label = if (state.chatImageMode) "draw" else "send",
                    accent = if (state.chatInput.isBlank() && state.chatAttachment == null)
                        Color(0xFF4A4A4A) else accent,
                    onClick = {
                        if (state.chatInput.isNotBlank() || state.chatAttachment != null) onSend()
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill(if (state.chatKbVisible) "hide" else "kbd", Color(0xFF9A9A9A), onToggleKb)
            Pill("img", if (state.chatImageMode) accent else Color(0xFF9A9A9A), onToggleImageMode)
            Pill("clip", Color(0xFF9A9A9A), onAttach)
        }

        AnimatedVisibility(
            visible = state.chatKbVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            RetroKeyboard(
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onDismiss = onToggleKb,
            )
        }
    }
}

@Composable
private fun Pill(label: String, accent: Color, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = 0.15f))
            .border(1.dp, accent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = type.appCard.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = accent)
    }
}
