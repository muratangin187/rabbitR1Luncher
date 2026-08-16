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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.camera.PhotoStore

/**
 * Photo grid. Two columns is the most that stays tappable at 480 px wide.
 *
 * Focus index 0 is the header/back row and 1.. are photos, matching the
 * convention every other list panel uses so `wheelUp` at the top edge unwinds.
 */
@Composable
fun GalleryPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.GALLERY,
        enter = fadeIn(tween(ANIM_OPEN_MS)) + slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) + slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val gridState = rememberLazyGridState()

        LaunchedEffect(state.galleryFocus) {
            val photoIdx = (state.galleryFocus - 1).coerceAtLeast(0)
            if (state.photos.isNotEmpty()) {
                gridState.animateScrollToItem(photoIdx.coerceAtMost(state.photos.lastIndex))
            }
        }

        Column(Modifier.fillMaxSize().background(Color.Black)) {
            AppPageHeader(
                title = "gallery",
                backFocused = state.galleryFocus == 0,
                onBack = onBack,
                themeColor = AppThemes.Camera,
                subtitle = if (state.photos.isEmpty()) null
                    else "${state.photos.size} photo${if (state.photos.size == 1) "" else "s"}",
            )

            if (state.photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "no photos yet",
                        style = type.appCard.copy(fontSize = 18.sp),
                        color = Color(0xFF6A6A6A),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(
                        items = state.photos,
                        key = { _, p -> p.path },
                    ) { idx, photo ->
                        GalleryTile(
                            photo = photo,
                            focused = state.galleryFocus == idx + 1,
                            onClick = { onOpen(idx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(
    photo: PhotoStore.Photo,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val type = LocalR1Type.current
    // Keyed on path: a deleted-and-replaced file at the same index must not
    // reuse the previous bitmap.
    val bmp = remember(photo.path) { PhotoStore.decode(photo, targetPx = 240)?.asImageBitmap() }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .focusAnim(focused)
            .border(if (focused) 3.dp else 1.dp, if (focused) AppThemes.Camera else Color(0xFF2A2A2A))
            .background(Color(0xFF0E0E0E))
            .clickable(onClick = onClick),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (photo.isAi) {
            Text(
                text = "ai",
                style = type.appCard.copy(fontSize = 13.sp),
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(AppThemes.Camera)
                    .padding(horizontal = 5.dp),
            )
        }
    }
}
