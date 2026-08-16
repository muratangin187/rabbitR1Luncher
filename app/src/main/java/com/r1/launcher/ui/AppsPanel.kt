package com.r1.launcher.ui

import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.r1.launcher.AppEntry
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.R
import java.util.concurrent.ConcurrentHashMap

private val iconCache = LruCache<String, ImageBitmap>(50)
private val labelCache = ConcurrentHashMap<String, String>()

private val APP_PALETTE = listOf(
    Color(0xFFFF003C), // Bright Red
    Color(0xFFFF2A9D), // Neon Pink
    Color(0xFF00E5FF), // Cyan/Light Blue
    Color(0xFF9D4EDD), // Lavender/Purple
    Color(0xFF00FF38), // Lime Green
    Color(0xFFFF6B00), // Bright Orange
    Color(0xFFFFD600), // Sunshine Yellow
    Color(0xFF2196F3), // Rabbit Blue
)

private val FOCUSED_HEIGHT = 108.dp
private val COLLAPSED_HEIGHT = 60.dp
private val CARD_SHAPE = RoundedCornerShape(14.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onAppClick: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.APPS,
        enter = fadeIn(tween(ANIM_OPEN_MS, easing = EnterEasing)) +
            slideInVertically(tween(ANIM_OPEN_MS, easing = EnterEasing)) { it / 3 } +
            scaleIn(tween(ANIM_OPEN_MS, easing = EnterEasing), initialScale = 0.96f),
        exit = fadeOut(tween(ANIM_CLOSE_MS, easing = ExitEasing)) +
            slideOutVertically(tween(ANIM_CLOSE_MS, easing = ExitEasing)) { it / 3 } +
            scaleOut(tween(ANIM_CLOSE_MS, easing = ExitEasing), targetScale = 1.04f),
    ) {
        val listState = rememberLazyListState()

        LaunchedEffect(state.appsFocus, state.apps.size) {
            if (state.apps.isEmpty()) return@LaunchedEffect
            val target = state.appsFocus.coerceIn(0, state.apps.lastIndex)
            val info = listState.layoutInfo
            val viewportStart = info.viewportStartOffset
            val viewportEnd = info.viewportEndOffset
            val targetItem = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (targetItem == null) {
                listState.animateScrollToItem(target)
                return@LaunchedEffect
            }
            val delta: Float = when {
                targetItem.offset + targetItem.size > viewportEnd ->
                    (targetItem.offset + targetItem.size - viewportEnd).toFloat()
                targetItem.offset < viewportStart ->
                    (targetItem.offset - viewportStart).toFloat()
                else -> 0f
            }
            if (kotlin.math.abs(delta) > 0.5f) {
                listState.animateScrollBy(
                    value = delta,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                )
            }
        }

        // Pull-down-from-top-of-list closes the apps panel (back to home).
        // Implemented via NestedScrollConnection so it doesn't conflict with
        // normal list scrolling: only the OVERSCROLL beyond the top edge
        // accumulates as "pull" — when the list is mid-scroll, downward drags
        // are consumed by the LazyColumn and never reach us. Pulling back UP
        // decrements the pull budget so the user can cancel before release.
        val density = LocalDensity.current
        val triggerPx = remember(density) { with(density) { 80.dp.toPx() } }
        val pulled = remember { mutableStateOf(0f) }
        val nestedScroll = remember(triggerPx, onBack) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.Drag && available.y < 0f && pulled.value > 0f) {
                        val consume = minOf(-available.y, pulled.value)
                        pulled.value -= consume
                        return Offset(0f, -consume)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.Drag && available.y > 0f) {
                        pulled.value += available.y
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pulled.value > triggerPx) onBack()
                    pulled.value = 0f
                    return Velocity.Zero
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy((-20).dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScroll),
        ) {
            itemsIndexed(
                items = state.apps,
                key = { _, entry -> appKey(entry) },
                contentType = { _, entry -> appContentType(entry) },
            ) { idx, entry ->
                Box(
                    modifier = Modifier
                        .zIndex(idx.toFloat()),
                ) {
                    AppCard(
                        entry = entry,
                        focused = idx == state.appsFocus,
                        background = cardBackground(entry, idx),
                        activateTick = state.appsPressTrigger,
                        onClick = { onAppClick(idx) },
                    )
                }
            }

            item {
                FolderTray(
                    modifier = Modifier
                        .zIndex((state.apps.size + 1).toFloat())
                        .fillMaxWidth()
                        .height(140.dp)
                        .offset(y = (-28).dp),
                )
            }
        }
        }
        // Top-center chevron hint: signals the pull-down-to-home gesture.
        // Drawn on top of the list (zIndex unset = above the LazyColumn).
        SwipeDownHint(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp),
        )
        }
    }
}

/** Top-center `﹀` chevron — mirror of HomeScreen's SwipeUpHint. Signals the
 *  pull-down gesture that returns to home. */
@Composable
private fun SwipeDownHint(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "swipeHintDown")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "swipeHintDownAlpha",
    )
    Canvas(modifier = modifier.size(width = 22.dp, height = 6.dp)) {
        val w = size.width
        val h = size.height
        val color = Color(0xFFFF6B00).copy(alpha = alpha)
        // Two strokes meeting at bottom-center → `﹀`
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(w / 2f, h),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w / 2f, h),
            end = Offset(w, 0f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun AppCard(
    entry: AppEntry,
    focused: Boolean,
    background: Color,
    activateTick: Int,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val pkg = when (entry) {
        is AppEntry.Real -> entry.info.activityInfo.packageName
        AppEntry.Settings -> "_r1_settings"
        AppEntry.OpenClaw -> "_r1_openclaw"
        AppEntry.Messages -> "_r1_messages"
        AppEntry.Terminal -> "_r1_terminal"
        AppEntry.Hermes -> "_r1_hermes"
        AppEntry.Meetings -> "_r1_meetings"
        AppEntry.Translator -> "_r1_translator"
        AppEntry.Testing -> "_r1_testing"
        AppEntry.Camera -> "_r1_camera"
    }
    // Only the matching synthetic resource is resolved — previous code called
    // painterResource() + stringResource() six times per card (one per
    // synthetic), so a 20-card grid did 120 resource lookups per recomposition.
    val syntheticPainter: Painter? = when (entry) {
        is AppEntry.Real -> null
        AppEntry.Settings -> painterResource(R.drawable.ic_settings)
        AppEntry.OpenClaw -> painterResource(R.drawable.ic_wifi_arc)
        AppEntry.Messages -> painterResource(R.drawable.ic_messages)
        AppEntry.Terminal -> painterResource(R.drawable.ic_terminal)
        AppEntry.Hermes -> painterResource(R.drawable.ic_hermes)
        AppEntry.Meetings -> painterResource(R.drawable.ic_meetings)
        AppEntry.Translator -> painterResource(R.drawable.ic_language)
        AppEntry.Testing -> painterResource(R.drawable.ic_thinking)
        AppEntry.Camera -> painterResource(R.drawable.ic_camera)
    }

    var iconPainter by remember(pkg) {
        val cached = iconCache.get(pkg)
        mutableStateOf<Painter?>(if (cached != null) BitmapPainter(cached) else null)
    }
    val syntheticLabel: String? = when (entry) {
        is AppEntry.Real -> null
        AppEntry.Settings -> stringResource(R.string.app_label_settings)
        AppEntry.OpenClaw -> stringResource(R.string.app_label_openclaw)
        AppEntry.Messages -> stringResource(R.string.app_label_messages)
        AppEntry.Terminal -> stringResource(R.string.app_label_terminal)
        AppEntry.Hermes -> stringResource(R.string.app_label_hermes)
        AppEntry.Meetings -> stringResource(R.string.app_label_meetings)
        AppEntry.Translator -> stringResource(R.string.app_label_translator)
        AppEntry.Testing -> stringResource(R.string.app_label_testing)
        AppEntry.Camera -> stringResource(R.string.app_label_camera)
    }
    var label by remember(pkg, syntheticLabel) {
        mutableStateOf(
            when (entry) {
                is AppEntry.Real -> labelCache[pkg] ?: pkg.substringAfterLast('.').lowercase()
                else -> syntheticLabel ?: ""
            },
        )
    }

    if (entry is AppEntry.Real) {
        val info = entry.info
        LaunchedEffect(pkg) {
            if (iconCache.get(pkg) == null) {
                val (loadedLabel, bitmap) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val l = info.loadLabel(pm).toString().lowercase()
                    val d = info.loadIcon(pm)
                    Pair(l, d.toBitmap(width = 144, height = 144).asImageBitmap())
                }
                iconCache.put(pkg, bitmap)
                labelCache[pkg] = loadedLabel
                label = loadedLabel
                iconPainter = BitmapPainter(bitmap)
            }
        }
    }

    val height by animateDpAsState(
        targetValue = if (focused) FOCUSED_HEIGHT else COLLAPSED_HEIGHT,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "cardHeight",
    )
    val focusGlow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "cardFocusGlow",
    )

    var pressTrigger by remember { mutableStateOf(0) }
    val pressScale = remember { Animatable(1f) }

    // Side-button activate path: state.appsPressTrigger increments without going
    // through the clickable. Mirror it into the local trigger so the same
    // animation runs and the same launch tail fires.
    var lastSeenActivate by remember { mutableStateOf(activateTick) }
    LaunchedEffect(activateTick, focused) {
        if (focused && activateTick > lastSeenActivate) {
            lastSeenActivate = activateTick
            if (pressTrigger == 0 || !pressScale.isRunning) {
                pressTrigger++
            }
        } else if (!focused) {
            lastSeenActivate = activateTick
        }
    }

    LaunchedEffect(pressTrigger) {
        if (pressTrigger > 0) {
            pressScale.animateTo(0.92f, animationSpec = tween(80, easing = LinearOutSlowInEasing))
            pressScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            onClick()
        }
    }

    val outer = Modifier
        .fillMaxWidth()
        .height(height)
        .graphicsLayer {
            scaleX = pressScale.value
            scaleY = pressScale.value
            rotationZ = (1f - pressScale.value) * 12f
        }
        .clip(CARD_SHAPE)
        .background(background)
        .border(2.dp, Color.Black, CARD_SHAPE)
        .clickable {
            if (pressTrigger == 0 || !pressScale.isRunning) {
                pressTrigger++
            }
        }

    Box(modifier = outer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = focusGlow * 0.10f
                }
                .background(Color.White),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            val effectivePainter = syntheticPainter ?: iconPainter
            Text(
                text = label.replaceFirstChar { it.titlecase() },
                color = Color.Black,
                style = LocalR1Type.current.appCard,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            if (effectivePainter != null) {
                Image(
                    painter = effectivePainter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Box(modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun cardBackground(entry: AppEntry, idx: Int): Color = when (entry) {
    is AppEntry.Real -> APP_PALETTE[idx % APP_PALETTE.size]
    AppEntry.Settings -> AppThemes.Settings
    AppEntry.OpenClaw -> AppThemes.OpenClaw
    AppEntry.Messages -> AppThemes.Messages
    AppEntry.Terminal -> AppThemes.Terminal
    AppEntry.Hermes -> AppThemes.Hermes
    AppEntry.Meetings -> AppThemes.Meetings
    AppEntry.Translator -> AppThemes.Translator
    AppEntry.Testing -> AppThemes.Testing
    AppEntry.Camera -> AppThemes.Camera
}

private fun appKey(entry: AppEntry): String = when (entry) {
    is AppEntry.Real -> entry.info.activityInfo.packageName + "/" + entry.info.activityInfo.name
    AppEntry.Settings -> "settings/settings"
    AppEntry.OpenClaw -> "openclaw/openclaw"
    AppEntry.Messages -> "messages/messages"
    AppEntry.Terminal -> "terminal/terminal"
    AppEntry.Hermes -> "hermes/hermes"
    AppEntry.Meetings -> "meetings/meetings"
    AppEntry.Translator -> "translator/translator"
    AppEntry.Testing -> "testing/testing"
    AppEntry.Camera -> "camera/camera"
}

private fun appContentType(entry: AppEntry): String = when (entry) {
    is AppEntry.Real -> "real"
    AppEntry.Settings -> "settings"
    AppEntry.OpenClaw -> "openclaw"
    AppEntry.Messages -> "messages"
    AppEntry.Terminal -> "terminal"
    AppEntry.Hermes -> "hermes"
    AppEntry.Meetings -> "meetings"
    AppEntry.Translator -> "translator"
    AppEntry.Testing -> "testing"
    AppEntry.Camera -> "camera"
}

class FolderShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val w = size.width
        val h = size.height
        val r = 10f * density.density
        val tr = 8f * density.density
        val tabW = w * 0.42f
        val tabH = h * 0.22f

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, tabH + r)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(0f, tabH, r * 2, tabH + r * 2),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(w - tabW, tabH)
            lineTo(w - tabW, tr)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(w - tabW, 0f, w - tabW + tr * 2, tr * 2),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(w - r, 0f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(w - r * 2, 0f, w, r * 2),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(w, h - r)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(w - r * 2, h - r * 2, w, h),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(r, h)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(0f, h - r * 2, r * 2, h),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

@Composable
fun FolderTray(modifier: Modifier = Modifier) {
    val folderShape = remember { FolderShape() }
    val info = remember { carrotOsInfo() }
    val labelGray = Color(0xFF6B3A00)
    Box(
        modifier = modifier
            .clip(folderShape)
            .background(Color(0xFFF57C00))
            .border(2.dp, Color.Black, folderShape),
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(start = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            Text(
                text = "carrotos",
                color = labelGray,
                style = LocalR1Type.current.appCard.copy(fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "v${info.version}",
                color = labelGray,
                style = LocalR1Type.current.tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info.build,
                color = labelGray,
                style = LocalR1Type.current.tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class CarrotOsInfo(val version: String, val build: String)

private fun carrotOsInfo(): CarrotOsInfo {
    // Fallback chain: OS-side carrot props win (set via device.mk on the
    // CarrotOS source tree); next the BuildConfig constants baked into the
    // launcher APK; finally the lineage / AOSP build fields. This lets the
    // tray show meaningful values even on system images that haven't been
    // rebuilt with ro.carrot.* wired up.
    val version = systemProp("ro.carrot.version")
        ?: com.r1.launcher.BuildConfig.CARROT_VERSION.takeIf { it.isNotBlank() }
        ?: systemProp("ro.lineage.version")
        ?: android.os.Build.VERSION.RELEASE
    val build = systemProp("ro.carrot.build.id")
        ?: com.r1.launcher.BuildConfig.CARROT_BUILD_ID.takeIf { it.isNotBlank() }
        ?: systemProp("ro.lineage.build.version")
        ?: android.os.Build.ID
    return CarrotOsInfo(
        version = version.lowercase(),
        build = build.lowercase(),
    )
}

private fun systemProp(key: String): String? = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    val get = cls.getMethod("get", String::class.java)
    (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
}.getOrNull()
