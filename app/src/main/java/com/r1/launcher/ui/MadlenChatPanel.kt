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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.ChatPhase
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.chat.ChatMsg
import com.r1.launcher.chat.Role
import kotlinx.coroutines.delay

/**
 * A Madlen conversation. Streaming is driven by the host activity; this panel
 * renders the transcript and the composer.
 *
 *   wheel up/down     scroll the transcript
 *   kbd               on-screen keyboard for typed input
 *   paste             pull the system clipboard into the draft
 */
@Composable
fun MadlenChatPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onToggleKb: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
    onSpeakToggle: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MADLEN_CHAT,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Madlen
        val listState = rememberLazyListState()
        val fontSize = state.madlenTextSize

        val liveRow = state.madlenWorking
        val rowCount = state.madlenMsgs.size + (if (liveRow) 1 else 0)

        LaunchedEffect(rowCount, state.madlenStreaming, state.madlenScrollTick) {
            if (state.madlenPinnedToBottom && rowCount > 0) {
                listState.animateScrollToItem((rowCount - 1).coerceAtLeast(0))
            }
        }
        LaunchedEffect(state.madlenScrollSeq) {
            if (state.madlenScrollSeq == 0) return@LaunchedEffect
            val h = listState.layoutInfo.viewportSize.height
            val step = (if (h > 0) h * 0.66f else 300f) * state.madlenScrollDir
            listState.animateScrollBy(step)
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo }
                .collect { info ->
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                    val total = info.totalItemsCount
                    state.madlenPinnedToBottom = total == 0 || last >= total - 1
                }
        }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            AppPageHeader(
                titleIconRes = R.drawable.ic_madlen,
                title = state.madlenTitle,
                backFocused = false,
                onBack = onBack,
                themeColor = accent,
                compact = true,
                subtitle = state.madlenModel.ifBlank { null },
                gearFocused = false,
                onGear = onSettings,
                trailingContent = {
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (state.madlenSpeak) accent.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable(onClick = onSpeakToggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_sound),
                            contentDescription = null,
                            colorFilter =
                                ColorFilter.tint(
                                    if (state.madlenSpeak) accent else Color(0xFF5A5A5A),
                                ),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.madlenMsgs.isEmpty() && !liveRow) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_madlen),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(accent.copy(alpha = 0.5f)),
                            modifier = Modifier.size(34.dp),
                        )
                        Text(
                            "start a conversation with madlen",
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color(0xFF7A7A7A),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "hold the side button to talk",
                            style = type.appCard.copy(fontSize = 13.sp),
                            color = Color(0xFF5A5A5A),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(
                            items = state.madlenMsgs,
                            key = { i, m -> "$i-${m.at}" },
                        ) { _, m ->
                            MBubble(msg = m, accent = accent, fontSize = fontSize)
                        }
                        if (liveRow) {
                            item(key = "live") {
                                if (state.madlenStreaming.isNotBlank()) {
                                    MBubble(
                                        msg = ChatMsg(Role.ASSISTANT, state.madlenStreaming, streaming = true),
                                        accent = accent,
                                        fontSize = fontSize,
                                    )
                                } else {
                                    MadlenPendingRow(state = state, accent = accent)
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !state.madlenPinnedToBottom,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 16.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.85f))
                                .border(1.dp, accent, RoundedCornerShape(8.dp))
                                .clickable {
                                    state.madlenPinnedToBottom = true
                                    state.madlenScrollTick++
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▼", style = type.appCard.copy(fontSize = 15.sp), color = accent)
                    }
                }
            }

            MadlenComposer(
                state = state,
                accent = accent,
                onSend = onSend,
                onStop = onStop,
                onRetry = onRetry,
                onToggleKb = onToggleKb,
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onPaste = onPaste,
            )
        }
    }
}

@Composable
private fun MadlenPendingRow(
    state: LauncherState,
    accent: Color,
) {
    val type = LocalR1Type.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.madlenWorking) {
        while (state.madlenWorking) {
            now = System.currentTimeMillis()
            delay(400)
        }
    }
    val secs = ((now - state.madlenPhaseStartedAt).coerceAtLeast(0L)) / 1000
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF141414))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots(accent)
        Text(
            text = if (state.madlenPhase == ChatPhase.TRANSCRIBING) "hearing" else "thinking",
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

@Composable
private fun TypingDots(accent: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
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
private fun MBubble(
    msg: ChatMsg,
    accent: Color,
    fontSize: Int,
) {
    val type = LocalR1Type.current
    val isUser = msg.role == Role.USER
    val style = type.appCard.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(if (isUser) 0.82f else 0.96f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isUser) accent.copy(alpha = 0.16f) else Color(0xFF141414))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (msg.text.isNotBlank()) {
                if (isUser) {
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
private fun MadlenComposer(
    state: LauncherState,
    accent: Color,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onToggleKb: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
) {
    val type = LocalR1Type.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0B0B))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Recording: live level + partial transcript, so the user can see it is
        // hearing them before they let go.
        if (state.madlenPhase == ChatPhase.RECORDING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFFFF3B30)))
                Text(
                    text = state.madlenPartial.ifBlank { "listening…" },
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = if (state.madlenPartial.isBlank()) Color(0xFFFF3B30) else Color.White,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF2A2A2A))) {
                Box(
                    Modifier
                        .fillMaxWidth((state.madlenMicLevel / 100f).coerceIn(0.02f, 1f))
                        .height(3.dp)
                        .background(Color(0xFFFF3B30)),
                )
            }
        }

        if (state.madlenWorking && state.madlenError.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.madlenError,
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = Color(0xFFFF6B4A),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (!state.madlenWorking && state.madlenError.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.madlenError,
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = Color(0xFFFF6B4A),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Pill("retry", accent, onRetry)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.madlenInput.ifBlank { "type with kbd, or paste" },
                style = type.appCard.copy(fontSize = 15.sp),
                color = if (state.madlenInput.isBlank()) Color(0xFF5A5A5A) else Color.White,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
            if (state.madlenWorking) {
                Pill("stop", Color(0xFFFF6B4A), onStop)
            } else {
                Pill(
                    label = "send",
                    accent = if (state.madlenInput.isBlank()) Color(0xFF4A4A4A) else accent,
                    onClick = { if (state.madlenInput.isNotBlank()) onSend() },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill(if (state.madlenKbVisible) "hide" else "kbd", Color(0xFF9A9A9A), onToggleKb)
            Pill("paste", Color(0xFF9A9A9A), onPaste)
        }

        AnimatedVisibility(
            visible = state.madlenKbVisible,
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
private fun Pill(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = 0.15f))
                .border(1.dp, accent, RoundedCornerShape(7.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = type.appCard.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = accent)
    }
}
