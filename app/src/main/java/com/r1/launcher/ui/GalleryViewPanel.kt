package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.AiStage
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import com.r1.launcher.camera.PhotoStore
import kotlinx.coroutines.delay

/**
 * Single-photo viewer.
 *
 * The photo is the screen. Every control floats on top of it as a small
 * overlay rather than taking a row of its own — on a 480x640 panel a
 * full-width button bar plus a status block left almost nothing for the image.
 *
 *   wheel up / down     previous / next photo
 *   side button HOLD    speak an edit; a new image lands in the gallery
 *   trash icon          delete (arms first, then confirms)
 */
@Composable
fun GalleryViewPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.GALLERY_VIEW,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        val type = LocalR1Type.current
        val photo = state.galleryCurrent
        val bmp = remember(photo?.path) {
            photo?.let { PhotoStore.decode(it, targetPx = 720)?.asImageBitmap() }
        }
        // Keyed on path so moving to the next photo disarms a pending delete.
        var armed by remember(photo?.path) { mutableStateOf(false) }
        // The gesture hint is the only thing on screen nobody can discover on
        // their own, so it shows briefly on arrival and then gets out of the way.
        var showHint by remember(photo?.path) { mutableStateOf(true) }
        LaunchedEffect(photo?.path) {
            showHint = true
            delay(2200)
            showHint = false
        }
        LaunchedEffect(armed) {
            if (armed) { delay(3000); armed = false }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "can't open this photo",
                        style = type.appCard.copy(fontSize = 17.sp),
                        color = Color(0xFF6A6A6A),
                    )
                }
            }

            AppPageHeader(
                backFocused = false,
                onBack = onBack,
                themeColor = AppThemes.Camera,
                compact = true,
                floating = true,
            )

            // Counter, top-right. Replaces the old header subtitle so the back
            // pill has the top-left corner to itself.
            if (state.photos.isNotEmpty()) {
                Text(
                    text = "${state.galleryIndex + 1}/${state.photos.size}" +
                        if (photo?.isAi == true) " ai" else "",
                    style = type.appCard.copy(fontSize = 14.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            // Trash, bottom-right. Two-step: first tap arms (turns red and
            // grows a confirm chip), second confirms. Auto-disarms after 3s.
            if (photo != null && !state.aiBusy) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (armed) {
                        Text(
                            text = "tap again",
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color(0xFFFF3B30),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.8f))
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                    OverlayIcon(
                        iconRes = R.drawable.ic_trash,
                        tint = if (armed) Color(0xFFFF3B30) else Color.White,
                        filled = armed,
                    ) {
                        if (armed) { armed = false; onDelete() } else armed = true
                    }
                }
            }

            // Hint + status share the bottom-left column so they never stack
            // into the trash button.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.72f)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedVisibility(
                    visible = showHint && state.aiStage == AiStage.IDLE,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(400)),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.78f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_voice),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(AppThemes.Camera),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "hold side button",
                            style = type.appCard.copy(fontSize = 14.sp),
                            color = Color(0xFFBDBDBD),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }

                AiStatusStrip(
                    state = state,
                    onRetry = onRetry,
                    onDismiss = onDismissStatus,
                )
            }
        }
    }
}

@Composable
private fun OverlayIcon(
    iconRes: Int,
    tint: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) tint.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(21.dp),
        )
    }
}
