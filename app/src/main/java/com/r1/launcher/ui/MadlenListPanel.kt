package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import kotlinx.coroutines.delay

/**
 * Madlen conversation history, fetched from the server.
 * Row map (lockstep with LauncherNav / LauncherActivity):
 *   0 back · 1 + new chat · 2..N+1 conversations · trailing gear = settings
 */
@Composable
fun MadlenListPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MADLEN_LIST,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Madlen
        val listState = rememberLazyListState()
        LaunchedEffect(state.madlenListFocus) {
            listState.animateScrollToItem(state.madlenListFocus.coerceIn(0, state.madlenHistory.size + 1))
        }

        // A pull refresh happens when the panel re-enters focus; the gear in
        // the header also triggers it explicitly. Show a subtle loading row.
        var armed by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(armed) {
            if (armed != null) {
                delay(2500)
                armed = null
            }
        }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            AppPageHeader(
                titleIconRes = R.drawable.ic_madlen,
                title = "madlen chats",
                backFocused = state.madlenListFocus == 0,
                onBack = onBack,
                themeColor = accent,
                subtitle = if (state.madlenHistory.isEmpty()) null else "${state.madlenHistory.size}",
                gearFocused = false,
                onGear = onSettings,
                trailingContent = {
                    RefreshPill(loading = state.madlenLoading, onClick = onRefresh, accent = accent)
                },
            )

            if (state.madlenError.isNotBlank() && state.madlenHistory.isEmpty()) {
                Text(
                    text = state.madlenError,
                    style = type.appCard.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = Color(0xFFFF6B4A),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "new") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusAnim(state.madlenListFocus == 1)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent.copy(alpha = 0.14f))
                                .border(
                                    if (state.madlenListFocus == 1) 2.dp else 1.dp,
                                    accent,
                                    RoundedCornerShape(10.dp),
                                ).clickable(onClick = onNew)
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

                if (state.madlenHistory.isEmpty() && !state.madlenLoading) {
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
                        items = state.madlenHistory,
                        key = { _, h -> h.id },
                    ) { idx, h ->
                        val focused = state.madlenListFocus == idx + 2
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusAnim(focused)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF121212))
                                    .border(
                                        if (focused) 2.dp else 1.dp,
                                        if (focused) accent else Color(0xFF262626),
                                        RoundedCornerShape(10.dp),
                                    ).clickable { onOpen(h.id) }
                                    .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = h.title,
                                    style = type.appCard.copy(fontSize = 16.sp),
                                    color = Color.White,
                                    maxLines = 2,
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
                                    text = relative(h.updatedAt),
                                    style = type.appCard.copy(fontSize = 12.sp),
                                    color = Color(0xFF4A4A4A),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshPill(
    loading: Boolean,
    onClick: () -> Unit,
    accent: Color,
) {
    val type = LocalR1Type.current
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(loading.not().let { if (it) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.4f) })
                .border(1.dp, accent, RoundedCornerShape(6.dp))
                .clickable(enabled = !loading, onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "…" else "⟳",
            style = type.appCard.copy(fontSize = 15.sp),
            color = accent,
        )
    }
}

/** Compact relative time — same helper every list panel uses. */
private fun relative(ts: Long): String {
    if (ts <= 0) return ""
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 604_800_000 -> "${d / 86_400_000}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(ts))
    }
}
