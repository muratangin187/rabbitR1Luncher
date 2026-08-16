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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
 * Settings → Credentials. Canonical edit point for every global API key
 * the launcher uses. Per the v3 design:
 *   - Anthropic key (used by Claude CLI in alpine chroot)
 *   - ElevenLabs key (used by every voice TTS + STT consumer)
 *   - Hermes bearer (used by HermesClient.streamChat)
 *   - ntfy.sh topic (used by NtfySubscriber)
 *   - Webhook bearer token (used by R1WebServer.handleNotifyPost) — view + regenerate only
 *
 * The underlying storage is unchanged — each key still lives in its own
 * *Prefs* object (VoicePrefs, HermesPrefs, NtfyPrefs, NotifPrefs) or on
 * disk (`/data/local/tmp/.anthropic_key`). This panel is the unified UI;
 * the storage stays where the existing readers expect it.
 *
 * Row layout (kept in lockstep with [com.r1.launcher.LauncherActivity.credentialsRowActivate]):
 *   0  < back
 *   1  elevenlabs       (status + masked tail; click → keyboard)
 *   2  hermes           (status + masked tail; click → keyboard)
 *   3  openai           (status + masked tail; click → keyboard)
 *   4  ntfy topic       (status + plain text; click → keyboard)
 *   5  webhook          (read-only display + click = regenerate, no keyboard)
 *   6  control secret   (read-only display + click = regenerate, no keyboard)
 */
@Composable
fun SettingsCredentialsPanel(
    state: LauncherState,
    onRowClick: (Int) -> Unit,
    onSaveField: (field: String, value: String) -> Unit,
    onPasteField: (field: String) -> Unit,
    onClearField: (field: String) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.SETTINGS_CREDENTIALS,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val items = listOf(
            CredentialsItem("__header__", "", false, ""),
            CredentialsItem("elevenlabs", "voice tts + stt", state.hasVoiceKey, state.voiceKeyTail),
            CredentialsItem("hermes", "hermes agent bearer", state.hasHermesKey, state.hermesKeyTail),
            CredentialsItem("openai", "camera transcribe + image edit", state.hasOpenAiKey, state.openAiKeyTail),
            CredentialsItem("ntfy.sh topic", "outbound notifications", state.ntfyTopic.isNotBlank(),
                state.ntfyTopic.takeLast(12)),
            CredentialsItem("webhook token", "tap to regenerate", true,
                state.webhookTokenDisplay),
            CredentialsItem("control secret", "adb broadcast key — tap to regenerate", true,
                state.controlSecretDisplay),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.credentialsFocus) {
            listState.animateScrollToItem(state.credentialsFocus.coerceIn(0, items.lastIndex))
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = items,
                    key = { idx, item -> if (idx == 0) "header" else item.label },
                ) { idx, item ->
                    if (idx == 0) {
                        AppPageHeader(
                            titleIconRes = R.drawable.ic_about,
                            title = "creds",
                            backFocused = state.credentialsFocus == 0,
                            onBack = { onRowClick(0) },
                            themeColor = AppThemes.Settings,
                        )
                    } else {
                        val subtitle = if (item.isSet)
                            "…${item.tail.ifBlank { "set" }}"
                        else
                            "not set"
                        val subtitleColor = if (item.isSet) Color(0xFF35D26F) else Color(0xFFFF4500)
                        SettingsRow(
                            label = item.label,
                            focused = idx == state.credentialsFocus,
                            toggleChecked = null,
                            subtitle = subtitle,
                            subtitleColor = subtitleColor,
                            onClick = { onRowClick(idx) },
                        )
                    }
                }
            }

            // Single keyboard overlay shared across all four typeable rows.
            // Driven by state.credentialsEditField (empty = closed). Mirrors
            // the SettingsVoicePanel pattern for layout + pill row.
            val field = state.credentialsEditField
            AnimatedVisibility(
                visible = field.isNotBlank(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val accent = Color(0xFFFF4500)
                val ok = Color(0xFF35D26F)
                val warn = Color(0xFFE53935)
                val isSecret = field != "ntfy_topic" // topic is shareable; everything else hides chars
                val input = state.credentialsEditInput
                val saveEnabled = input.trim().isNotBlank() || field == "ntfy_topic"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labelFor(field),
                            style = type.appCard.copy(fontSize = 16.sp),
                            color = accent,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = hintFor(field),
                            style = type.appCard.copy(fontSize = 11.sp),
                            color = Color.DarkGray,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .background(Color(0xFF101010))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        val displayText = when {
                            input.isEmpty() -> placeholderFor(field)
                            isSecret -> mask(input) + "_"
                            else -> input + "_"
                        }
                        Text(
                            text = displayText,
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = if (input.isEmpty()) Color(0xFF707070) else Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CredPill("save", if (saveEnabled) ok else Color.DarkGray, saveEnabled, Modifier.weight(1f)) {
                            onSaveField(field, input.trim())
                        }
                        CredPill("paste", accent, true, Modifier.weight(1f)) {
                            onPasteField(field)
                        }
                        CredPill("clear", warn, true, Modifier.weight(1f)) {
                            onClearField(field)
                        }
                        CredPill("close", Color.White, true, Modifier.weight(1f)) {
                            state.credentialsEditField = ""
                            state.credentialsEditInput = ""
                        }
                    }

                    RetroKeyboard(
                        onKeyPress = { ch -> state.credentialsEditInput += ch },
                        onBackspace = {
                            if (state.credentialsEditInput.isNotEmpty()) {
                                state.credentialsEditInput = state.credentialsEditInput.dropLast(1)
                            }
                        },
                        onDismiss = {
                            state.credentialsEditField = ""
                            state.credentialsEditInput = ""
                        },
                    )
                }
            }
        }
    }
}

private data class CredentialsItem(
    val label: String,
    val subtitle: String,
    val isSet: Boolean,
    val tail: String,
)

private fun labelFor(field: String): String = when (field) {
    "anthropic" -> "anthropic key"
    "elevenlabs" -> "elevenlabs key"
    "hermes" -> "hermes bearer"
    "ntfy_topic" -> "ntfy.sh topic"
    else -> field
}

private fun hintFor(field: String): String = when (field) {
    "anthropic" -> "sk-ant-…"
    "elevenlabs" -> "sk_… or 32-char hex"
    "hermes" -> "bearer token"
    "ntfy_topic" -> "unguessable name"
    else -> ""
}

private fun placeholderFor(field: String): String = when (field) {
    "ntfy_topic" -> "type a topic name"
    else -> "type or paste"
}

private fun mask(s: String): String {
    if (s.length <= 8) return "*".repeat(s.length)
    return s.take(3) + "…" + s.takeLast(4)
}

@Composable
private fun CredPill(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    val borderColor = if (enabled) color else Color(0xFF333333)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.appCard.copy(fontSize = 14.sp),
            color = borderColor,
        )
    }
}
