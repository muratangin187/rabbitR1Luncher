package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.r1.launcher.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.camera.PhotoStore
import kotlinx.coroutines.delay

/**
 * Full-screen camera. Layout is deliberately sparse — the preview is the
 * screen, and everything else floats over it.
 *
 *   wheel up / down   flip the lens (motor move)
 *   wheel press       shutter
 *   side button tap   shutter (routes through the same `activate`)
 *   flip icon         flip the lens
 *   bottom-left thumb open the gallery
 */
@Composable
fun CameraPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    onOpenGallery: () -> Unit,
    onCameraEvent: (R1CameraView.Event) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.CAMERA,
        enter = fadeIn(tween(ANIM_OPEN_MS)),
        exit = fadeOut(tween(ANIM_CLOSE_MS)),
    ) {
        val type = LocalR1Type.current
        var cameraView by remember { mutableStateOf<R1CameraView?>(null) }

        // Hold the lens wherever the user pointed it for the whole session and
        // park it on the way out. Keyed on cameraFacing so a flip re-issues the
        // motor write; the panel-level DisposableEffect is the only owner of
        // motor lifetime (R1CameraView deliberately never touches it).
        DisposableEffect(state.cameraFacing) {
            setMotorOrientation(state.cameraFacing)
            onDispose { }
        }
        DisposableEffect(Unit) {
            onDispose { setMotorOrientation(MOTOR_HOME) }
        }
        // The host owns "when to shoot" (wheel press, side button, on-screen
        // shutter all funnel into cameraShutter()), but only the panel holds
        // the view instance — so the request crosses as a counter.
        LaunchedEffect(state.cameraShutterRequest, cameraView) {
            if (state.cameraShutterRequest > 0) cameraView?.capture()
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // At FACE the sensor still reports its world-facing
                    // orientation, so the preview comes through mirrored the
                    // way a phone's selfie view does. Flipping it back matches
                    // what people expect from a front camera. This transforms
                    // only the View's display — the captured JPEG is untouched,
                    // same as stock front-camera behaviour.
                    .graphicsLayer(scaleX = if (state.cameraFacingIsFront) -1f else 1f),
                factory = { ctx ->
                    R1CameraView(ctx) { ev -> onCameraEvent(ev) }
                        .also { cameraView = it; it.start() }
                },
                onRelease = { view ->
                    view.stop()
                    if (cameraView === view) cameraView = null
                },
            )

            // Shutter flash. Keyed on a counter rather than a boolean so two
            // captures in a row each get their own flash.
            var flashing by remember { mutableStateOf(false) }
            LaunchedEffect(state.cameraShutterFlash) {
                if (state.cameraShutterFlash > 0) {
                    flashing = true
                    delay(110)
                    flashing = false
                }
            }
            if (flashing) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.75f)))
            }

            if (!state.cameraReady && state.cameraError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "starting camera…",
                        style = type.appCard.copy(fontSize = 18.sp),
                        color = Color.White.copy(alpha = 0.85f),
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

            Text(
                text = if (state.cameraFacingIsFront) "front" else "rear",
                style = type.appCard.copy(fontSize = 14.sp),
                color = AppThemes.Camera,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )

            state.cameraError?.let { err ->
                Text(
                    text = err,
                    style = type.appCard.copy(fontSize = 16.sp),
                    color = Color(0xFFFFC107),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // Bottom bar: last shot | shutter | flip
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LastShotThumb(state = state, onClick = onOpenGallery)

                ShutterButton(enabled = state.cameraReady && !state.cameraCapturing, onClick = onShutter)

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClick = onFlip),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_reboot),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(AppThemes.Camera),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LastShotThumb(state: LauncherState, onClick: () -> Unit) {
    val type = LocalR1Type.current
    val newest = state.photos.firstOrNull()
    val bmp = remember(newest?.path) {
        newest?.let { PhotoStore.decode(it, targetPx = 128)?.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (newest == null) Color(0xFF3A3A3A) else Color.White, RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable(enabled = newest != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.photos.size > 1) {
                Text(
                    text = "${state.photos.size}",
                    style = type.appCard.copy(fontSize = 13.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 3.dp),
                )
            }
        } else {
            Text("—", style = type.appCard.copy(fontSize = 20.sp), color = Color(0xFF3A3A3A))
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val ring = if (enabled) Color.White else Color(0xFF4A4A4A)
    Box(
        modifier = Modifier
            .size(66.dp)
            .border(3.dp, ring)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(46.dp).background(ring))
    }
}
