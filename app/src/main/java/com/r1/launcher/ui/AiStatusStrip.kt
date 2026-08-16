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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.r1.launcher.AiStage
import com.r1.launcher.LauncherState
import com.r1.launcher.R
import com.r1.launcher.camera.OpenAiClient
import kotlinx.coroutines.delay

/**
 * Compact floating status pill for the AI image job.
 *
 * Earlier this was a full-width block with a title, the transcript, a
 * secondary message line and full-width buttons — it ate a third of a 480x640
 * screen and buried the photo. This version keeps the same information budget
 * (which stage, what was heard, how long, and a way out of a failure) but
 * spends it on one line plus a progress rule, floats over the image, and drops
 * every sentence that wasn't telling the user something they couldn't already
 * see.
 */
@Composable
fun AiStatusStrip(
    state: LauncherState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current

    AnimatedVisibility(
        visible = state.aiStage != AiStage.IDLE,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
        modifier = modifier,
    ) {
        val accent = when (state.aiStage) {
            AiStage.RECORDING -> Color(0xFFFF3B30)
            AiStage.TRANSCRIBING -> Color(0xFFFFC107)
            AiStage.GENERATING -> AppThemes.Camera
            AiStage.DONE -> Color(0xFF35D26F)
            AiStage.FAILED -> Color(0xFFFF6B4A)
            AiStage.IDLE -> Color.White
        }

        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(state.aiStage) {
            while (state.aiStage == AiStage.GENERATING || state.aiStage == AiStage.TRANSCRIBING) {
                nowMs = System.currentTimeMillis()
                delay(500)
            }
        }
        val elapsedMs = (nowMs - state.aiStartedAtMs).coerceAtLeast(0L)

        // One short label per stage. No sentences — the progress rule already
        // says "working" and the elapsed counter already says "how long".
        val label = when (state.aiStage) {
            AiStage.RECORDING -> "listening"
            AiStage.TRANSCRIBING -> "hearing"
            AiStage.GENERATING -> "painting"
            AiStage.DONE -> "added"
            AiStage.FAILED -> state.aiMessage.ifBlank { "failed" }
            AiStage.IDLE -> ""
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StagePulse(accent = accent, animate = state.aiBusy)
                Text(
                    text = label,
                    style = type.appCard.copy(fontSize = 17.sp),
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 7.dp).weight(1f),
                )
                if (state.aiStage == AiStage.GENERATING || state.aiStage == AiStage.TRANSCRIBING) {
                    Text(
                        text = "${elapsedMs / 1000}s",
                        style = type.appCard.copy(fontSize = 15.sp),
                        color = Color(0xFF8A8A8A),
                    )
                }
                if (state.aiStage == AiStage.FAILED) {
                    IconPill(R.drawable.ic_reboot, accent, onRetry)
                    IconPill(R.drawable.ic_send, Color(0xFF8A8A8A), onDismiss)
                }
                if (state.aiStage == AiStage.DONE) {
                    IconPill(R.drawable.ic_send, accent, onDismiss)
                }
            }

            // The transcript is the one piece of text worth keeping: it is the
            // only way to catch a mishearing before waiting out the generation.
            if (state.aiPrompt.isNotBlank() && state.aiStage != AiStage.FAILED) {
                Text(
                    text = state.aiPrompt,
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = Color(0xFFBDBDBD),
                    maxLines = 2,
                )
            }

            when (state.aiStage) {
                AiStage.RECORDING -> Rule(state.aiLevel / 100f, accent)
                // Eases toward 95% against the measured p50 and holds there —
                // an over-long job must read as "still working", never "stuck".
                AiStage.GENERATING -> Rule(
                    (elapsedMs.toFloat() / OpenAiClient.TYPICAL_IMAGE_MS.toFloat()).coerceIn(0f, 0.95f),
                    accent,
                )
                AiStage.TRANSCRIBING -> Rule(null, accent)
                else -> Unit
            }
        }
    }
}

/** Breathing square — retro-idiom stand-in for a spinner. */
@Composable
private fun StagePulse(accent: Color, animate: Boolean) {
    val alpha = if (animate) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        a
    } else 1f
    Box(Modifier.size(10.dp).background(accent.copy(alpha = alpha)))
}

/** [fraction] null = indeterminate sweep. */
@Composable
private fun Rule(fraction: Float?, accent: Color) {
    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF2A2A2A))) {
        if (fraction == null) {
            val t = rememberInfiniteTransition(label = "sweep")
            val x by t.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "sweepX",
            )
            Box(
                Modifier
                    .fillMaxWidth(0.3f)
                    .height(3.dp)
                    .padding(start = (x * 200).dp)
                    .background(accent),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun IconPill(iconRes: Int, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(16.dp),
        )
    }
}
