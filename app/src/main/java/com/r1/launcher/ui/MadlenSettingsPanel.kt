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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Madlen settings. Row map (lockstep with LauncherActivity.madlenSettingsActivate):
 *   0 back · 1 base url · 2 model · 3 speak · 4 text size · 5 logout
 */
@Composable
fun MadlenSettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MADLEN_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Madlen
        val listState = rememberLazyListState()
        LaunchedEffect(state.madlenSettingsFocus) {
            listState.animateScrollToItem(state.madlenSettingsFocus.coerceIn(0, 5))
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hdr") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_madlen,
                        title = "madlen setup",
                        backFocused = state.madlenSettingsFocus == 0,
                        onBack = { onRowClick(0) },
                        themeColor = accent,
                    )
                }
                item(key = "base") {
                    SettingsRow(
                        label = "api base url",
                        focused = state.madlenSettingsFocus == 1,
                        toggleChecked = null,
                        subtitle = state.madlenBaseUrl,
                        subtitleColor = Color(0xFF9A9A9A),
                        onClick = { onRowClick(1) },
                    )
                }
                item(key = "model") {
                    SettingsRow(
                        label = "model",
                        focused = state.madlenSettingsFocus == 2,
                        toggleChecked = null,
                        subtitle = state.madlenModel.ifBlank { com.r1.launcher.madlen.MadlenPrefs.DEFAULT_MODEL },
                        subtitleColor = Color(0xFF9A9A9A),
                        onClick = { onRowClick(2) },
                    )
                }
                item(key = "speak") {
                    SettingsRow(
                        label = "speak replies",
                        focused = state.madlenSettingsFocus == 3,
                        toggleChecked = state.madlenSpeak,
                        subtitle = if (state.madlenSpeak) "replies read aloud" else "",
                        subtitleColor = Color(0xFF6A6A6A),
                        onClick = { onRowClick(3) },
                    )
                }
                item(key = "font") {
                    SettingsRow(
                        label = "text size",
                        focused = state.madlenSettingsFocus == 4,
                        toggleChecked = null,
                        subtitle = "${state.madlenTextSize}sp — tap to cycle",
                        subtitleColor = Color(0xFF9A9A9A),
                        onClick = { onRowClick(4) },
                    )
                }
                item(key = "logout") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusAnim(state.madlenSettingsFocus == 5)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1A0E0C))
                                .border(
                                    if (state.madlenSettingsFocus == 5) 2.dp else 1.dp,
                                    Color(0xFFFF3B30),
                                    RoundedCornerShape(10.dp),
                                ).clickable { onRowClick(5) }
                                .padding(horizontal = 11.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "log out",
                            style = type.appCard.copy(fontSize = 17.sp),
                            color = Color(0xFFFF3B30),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible =
                    state.madlenEditField.isNotBlank() &&
                        (state.madlenEditField == "base" || state.madlenEditField == "model"),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (state.madlenEditField == "base") "api base url" else "model",
                        style = type.appCard.copy(fontSize = 15.sp),
                        color = accent,
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .background(Color(0xFF101010))
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = state.madlenEditInput.ifBlank { "type…" } + "_",
                            style = type.appCard.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = if (state.madlenEditInput.isBlank()) Color(0xFF6A6A6A) else Color.White,
                            maxLines = 1,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditPill("save", Color(0xFF35D26F), Modifier.weight(1f)) { onRowClick(SAVE_ROW) }
                        EditPill("clear", Color(0xFFE53935), Modifier.weight(1f)) { state.madlenEditInput = "" }
                        EditPill("close", Color.White, Modifier.weight(1f)) {
                            state.madlenEditField = ""
                            state.madlenEditInput = ""
                        }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch -> state.madlenEditInput += ch },
                        onBackspace = {
                            if (state.madlenEditInput.isNotEmpty()) {
                                state.madlenEditInput = state.madlenEditInput.dropLast(1)
                            }
                        },
                        onDismiss = {
                            state.madlenEditField = ""
                            state.madlenEditInput = ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditPill(
    label: String,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = 0.15f))
                .border(1.dp, accent, RoundedCornerShape(7.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = type.appCard.copy(fontSize = 14.sp), color = accent)
    }
}
