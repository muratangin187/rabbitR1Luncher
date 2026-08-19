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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R

/**
 * Madlen sign-in. Username + password are committed into prefs; the token is
 * minted through madlen's Firebase flow. Row map:
 *   0 back · 1 username · 2 password · 3 log in
 *
 * Fields are edited through the shared RetroKeyboard overlay (same pattern as
 * the chat/translator settings keyboards).
 */
@Composable
fun MadlenLoginPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenField: (String) -> Unit,
    onCommitField: () -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onCloseKb: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.MADLEN_LOGIN,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Madlen
        val listState = rememberLazyListState()
        LaunchedEffect(state.madlenLoginFocus) {
            listState.animateScrollToItem(state.madlenLoginFocus.coerceIn(0, 3))
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hdr") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_madlen,
                        title = "madlen login",
                        backFocused = state.madlenLoginFocus == 0,
                        onBack = onBack,
                        themeColor = accent,
                    )
                }
                item(key = "user") {
                    FieldRow(
                        label = "madlen email",
                        hint = "murat@madlen.io",
                        value = state.madlenUser,
                        focused = state.madlenLoginFocus == 1,
                        accent = accent,
                        onClick = { onOpenField("user") },
                    )
                }
                item(key = "pass") {
                    FieldRow(
                        label = "password",
                        hint = "••••••••",
                        value = "•".repeat(state.madlenPass.length),
                        focused = state.madlenLoginFocus == 2,
                        accent = accent,
                        onClick = { onOpenField("pass") },
                    )
                }
                item(key = "login") {
                    val busy = state.madlenLoginBusy
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusAnim(state.madlenLoginFocus == 3)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (busy) {
                                        accent.copy(alpha = 0.4f)
                                    } else {
                                        accent.copy(alpha = 0.9f)
                                    },
                                ).clickable(enabled = !busy) { onLogin() }
                                .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (busy) "logging in…" else "log in",
                            style = type.appCard.copy(fontSize = 18.sp),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (state.madlenError.isNotBlank()) {
                    item(key = "err") {
                        Text(
                            text = state.madlenError,
                            style = type.appCard.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = Color(0xFFFF6B4A),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                item(key = "note") {
                    Text(
                        text = "connects to the madlen teacher-chat API",
                        style = type.appCard.copy(fontSize = 12.sp),
                        color = Color(0xFF5A5A5A),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = state.madlenEditField.isNotBlank(),
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
                        text =
                            state.madlenEditField?.let {
                                if (it == "user") "madlen email" else "password"
                            } ?: "",
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
                        EditPill("save", Color(0xFF35D26F), Modifier.weight(1f)) { onCommitField() }
                        EditPill("clear", Color(0xFFE53935), Modifier.weight(1f)) { state.madlenEditInput = "" }
                        EditPill("close", Color.White, Modifier.weight(1f)) { onCloseKb() }
                    }
                    RetroKeyboard(
                        onKeyPress = onKeyPress,
                        onBackspace = onBackspace,
                        onDismiss = onCloseKb,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    hint: String,
    value: String,
    focused: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    Column(
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
                ).clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(label, style = type.appCard.copy(fontSize = 17.sp), color = Color.White)
        Text(
            text = value.ifBlank { hint },
            style = type.appCard.copy(fontSize = 14.sp),
            color = if (value.isBlank()) Color(0xFF5A5A5A) else Color(0xFF9A9A9A),
            modifier = Modifier.padding(top = 3.dp),
        )
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
