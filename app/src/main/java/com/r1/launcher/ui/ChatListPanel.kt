package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation history.
 *
 * Row 0 is the header, row 1 is "new chat", rows 2+ are conversations — the
 * same "0 = back" convention every other list panel uses, so wheel-up at the
 * top unwinds to the apps grid.
 */
@Composable
fun ChatListPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.CHAT_LIST,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Chat
        val listState = rememberLazyListState()
        // Which row has an armed delete. Only one at a time, auto-disarms.
        var armed by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(armed) { if (armed != null) { delay(3000); armed = null } }

        LaunchedEffect(state.chatListFocus) {
            listState.animateScrollToItem(
                state.chatListFocus.coerceIn(0, (state.chatHistory.size + 1))
            )
        }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            AppPageHeader(
                titleIconRes = R.drawable.ic_chat,
                title = "chats",
                backFocused = state.chatListFocus == 0,
                onBack = onBack,
                themeColor = accent,
                subtitle = if (state.chatHistory.isEmpty()) null
                    else "${state.chatHistory.size}",
            )

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "new") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusAnim(state.chatListFocus == 1)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.14f))
                            .border(
                                if (state.chatListFocus == 1) 2.dp else 1.dp,
                                accent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(onClick = onNew)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+", style = type.appCard.copy(fontSize = 21.sp), color = accent)
                        Text(
                            "new chat",
                            style = type.appCard.copy(fontSize = 18.sp),
                            color = accent,
                            modifier = Modifier.padding(start = 9.dp),
                        )
                    }
                }

                if (state.chatHistory.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "no conversations yet",
                            style = type.appCard.copy(fontSize = 15.sp),
                            color = Color(0xFF5A5A5A),
                            modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = state.chatHistory,
                        key = { _, h -> h.id },
                    ) { idx, h ->
                        val focused = state.chatListFocus == idx + 2
                        val isArmed = armed == h.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusAnim(focused)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF121212))
                                .border(
                                    if (focused) 2.dp else 1.dp,
                                    if (focused) accent else Color(0xFF262626),
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onOpen(h.id) }
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = h.title,
                                    style = type.appCard.copy(fontSize = 16.sp),
                                    color = Color.White,
                                    maxLines = 1,
                                )
                                if (h.preview.isNotBlank()) {
                                    Text(
                                        text = h.preview,
                                        style = type.appCard.copy(fontSize = 13.sp),
                                        color = Color(0xFF6A6A6A),
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = "${relative(h.updatedAt)} · ${h.count}",
                                    style = type.appCard.copy(fontSize = 12.sp),
                                    color = Color(0xFF4A4A4A),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (isArmed) Color(0xFFFF3B30).copy(alpha = 0.3f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        if (isArmed) { armed = null; onDelete(h.id) } else armed = h.id
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_trash),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(
                                        if (isArmed) Color(0xFFFF3B30) else Color(0xFF4A4A4A)
                                    ),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact relative time — absolute dates waste width at this size. */
private fun relative(ts: Long): String {
    if (ts <= 0) return ""
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 604_800_000 -> "${d / 86_400_000}d"
        else -> SimpleDateFormat("d MMM", Locale.US).format(Date(ts))
    }
}
