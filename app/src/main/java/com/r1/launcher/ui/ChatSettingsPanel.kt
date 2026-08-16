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
import com.r1.launcher.chat.Provider

/**
 * In-app chat settings. Everything here is behaviour; credentials deliberately
 * live in Settings → Credentials so there is exactly one place on the device
 * that holds keys.
 *
 * Row map (kept in lockstep with LauncherActivity.chatSettingsActivate):
 *   0 back · 1 provider · 2 model · 3 speak · 4 voice auto-send
 *   5 system prompt · 6 reset prompt · 7 font size · 8 delete all chats
 */
@Composable
fun ChatSettingsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.CHAT_SETTINGS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Chat
        val listState = rememberLazyListState()
        LaunchedEffect(state.chatSettingsFocus) {
            listState.animateScrollToItem(state.chatSettingsFocus.coerceIn(0, 8))
        }
        val provider = Provider.byId(state.chatProviderId)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hdr") {
                    AppPageHeader(
                        titleIconRes = R.drawable.ic_settings,
                        title = "chat setup",
                        backFocused = state.chatSettingsFocus == 0,
                        onBack = { onRowClick(0) },
                        themeColor = accent,
                    )
                }
                item(key = "provider") {
                    SettingsRow(
                        label = "provider",
                        focused = state.chatSettingsFocus == 1,
                        toggleChecked = null,
                        subtitle = provider.label + if (!provider.available) " (soon)" else "",
                        subtitleColor = if (provider.available) Color(0xFF35D26F) else Color(0xFF8A6A2A),
                        onClick = { onRowClick(1) },
                    )
                }
                item(key = "model") {
                    SettingsRow(
                        label = "model",
                        focused = state.chatSettingsFocus == 2,
                        toggleChecked = null,
                        subtitle = state.chatModel.ifBlank { provider.defaultModel },
                        subtitleColor = Color(0xFF9A9A9A),
                        onClick = { onRowClick(2) },
                    )
                }
                item(key = "speak") {
                    SettingsRow(
                        label = "speak replies",
                        focused = state.chatSettingsFocus == 3,
                        toggleChecked = state.chatSpeak,
                        subtitle = if (state.chatSpeak) "adds a 'keep it short' line to the prompt" else "",
                        subtitleColor = Color(0xFF6A6A6A),
                        onClick = { onRowClick(3) },
                    )
                }
                item(key = "autosend") {
                    SettingsRow(
                        label = "voice auto-send",
                        focused = state.chatSettingsFocus == 4,
                        toggleChecked = state.chatVoiceAutoSend,
                        subtitle = if (state.chatVoiceAutoSend) "" else "transcript lands in the draft",
                        subtitleColor = Color(0xFF6A6A6A),
                        onClick = { onRowClick(4) },
                    )
                }
                item(key = "sysprompt") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusAnim(state.chatSettingsFocus == 5)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF121212))
                            .border(
                                if (state.chatSettingsFocus == 5) 2.dp else 1.dp,
                                if (state.chatSettingsFocus == 5) accent else Color(0xFF262626),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onRowClick(5) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                    ) {
                        Text("system prompt", style = type.appCard.copy(fontSize = 17.sp), color = Color.White)
                        Text(
                            text = state.chatSystemPrompt.ifBlank { "(empty)" },
                            style = type.appCard.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = Color(0xFF7A7A7A),
                            maxLines = 4,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (state.chatSpeak) {
                            Text(
                                text = "+ speech instruction while 'speak replies' is on",
                                style = type.appCard.copy(fontSize = 12.sp),
                                color = accent.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                item(key = "reset") {
                    SettingsRow(
                        label = "reset prompt",
                        focused = state.chatSettingsFocus == 6,
                        toggleChecked = null,
                        subtitle = "back to the default",
                        subtitleColor = Color(0xFF6A6A6A),
                        onClick = { onRowClick(6) },
                    )
                }
                item(key = "font") {
                    SettingsRow(
                        label = "text size",
                        focused = state.chatSettingsFocus == 7,
                        toggleChecked = null,
                        subtitle = "${state.chatTextSize}sp — tap to cycle",
                        subtitleColor = Color(0xFF9A9A9A),
                        onClick = { onRowClick(7) },
                    )
                }
                item(key = "wipe") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusAnim(state.chatSettingsFocus == 8)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A0E0C))
                            .border(
                                if (state.chatSettingsFocus == 8) 2.dp else 1.dp,
                                Color(0xFFFF3B30),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onRowClick(8) }
                            .padding(horizontal = 11.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "delete all chats",
                            style = type.appCard.copy(fontSize = 17.sp),
                            color = Color(0xFFFF3B30),
                        )
                    }
                }
                item(key = "note") {
                    Text(
                        "api keys live in settings → creds",
                        style = type.appCard.copy(fontSize = 12.sp),
                        color = Color(0xFF4A4A4A),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
                    )
                }
            }

            // Keyboard for the two typeable rows. This panel owns it — the
            // credentials overlay lives inside its own panel and is unreachable
            // from here.
            AnimatedVisibility(
                visible = state.chatEditField.isNotBlank(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val isPrompt = state.chatEditField == "system"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isPrompt) "system prompt" else "model",
                        style = type.appCard.copy(fontSize = 15.sp),
                        color = accent,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = state.chatEditInput.ifBlank { "type…" } + "_",
                            style = type.appCard.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = if (state.chatEditInput.isBlank()) Color(0xFF6A6A6A) else Color.White,
                            maxLines = if (isPrompt) 4 else 1,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditPill("save", Color(0xFF35D26F), Modifier.weight(1f)) { onRowClick(SAVE_ROW) }
                        EditPill("clear", Color(0xFFE53935), Modifier.weight(1f)) { state.chatEditInput = "" }
                        EditPill("close", Color.White, Modifier.weight(1f)) {
                            state.chatEditField = ""; state.chatEditInput = ""
                        }
                    }
                    RetroKeyboard(
                        onKeyPress = { ch -> state.chatEditInput += ch },
                        onBackspace = {
                            if (state.chatEditInput.isNotEmpty()) {
                                state.chatEditInput = state.chatEditInput.dropLast(1)
                            }
                        },
                        onDismiss = { state.chatEditField = ""; state.chatEditInput = "" },
                    )
                }
            }
        }
    }
}

/** Sentinel row id the overlay uses to ask the host to commit the field. */
const val SAVE_ROW = 99

@Composable
private fun EditPill(label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val type = LocalR1Type.current
    Box(
        modifier = modifier
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
