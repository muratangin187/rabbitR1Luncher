package com.r1.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard page header used across every top-level and sub-panel.
 *
 * Default (full) layout — two rows:
 *
 *   [< back]              [+]  [⚙]      ← optional buttons
 *   📋 title                             ← icon + title row
 *
 * Compact layout (`compact = true`) — single row, used by chat / terminal / claude
 * / transcriber-detail style panels where the panel content is the focal element:
 *
 *   [< back]  subtitle           [trailing]  [+]  [⚙]
 *
 * Floating layout (`floating = true`, implies compact) — same single-row content
 * but rendered against a top-down black→transparent gradient backdrop so a
 * scrolling chat list reads cleanly behind it. The caller positions the header
 * inside an outer Box; pass `Modifier.align(Alignment.TopCenter)` etc.
 *
 * Pass [themeColor] to brand the title text + title icon + back-pill border with
 * the same color as the app's tile on the apps grid (see [AppThemes]). The
 * focus highlight stays a consistent orange across all apps so wheel navigation
 * reads the same everywhere.
 */
private val FOCUS_HIGHLIGHT = Color(0xFFFF4500)
private val DEFAULT_THEME = Color(0xFFFF6B00)

@Composable
fun AppPageHeader(
    titleIconRes: Int = 0,
    title: String = "",
    backFocused: Boolean,
    onBack: () -> Unit,
    plusFocused: Boolean = false,
    onPlus: (() -> Unit)? = null,
    gearFocused: Boolean = false,
    onGear: (() -> Unit)? = null,
    clearFocused: Boolean = false,
    onClear: (() -> Unit)? = null,
    themeColor: Color = DEFAULT_THEME,
    compact: Boolean = false,
    floating: Boolean = false,
    subtitle: String? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current
    val isCompact = compact || floating

    if (floating) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC000000),
                            Color(0x66000000),
                            Color.Transparent,
                        ),
                    ),
                ),
        ) {
            HeaderRow(
                backFocused = backFocused,
                onBack = onBack,
                themeColor = themeColor,
                subtitle = subtitle,
                trailingContent = trailingContent,
                plusFocused = plusFocused,
                onPlus = onPlus,
                gearFocused = gearFocused,
                onGear = onGear,
                clearFocused = clearFocused,
                onClear = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
            )
        }
        return
    }

    if (isCompact) {
        HeaderRow(
            backFocused = backFocused,
            onBack = onBack,
            themeColor = themeColor,
            subtitle = subtitle,
            trailingContent = trailingContent,
            plusFocused = plusFocused,
            onPlus = onPlus,
            gearFocused = gearFocused,
            onGear = onGear,
            clearFocused = clearFocused,
            onClear = onClear,
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HeaderBackPill(focused = backFocused, themeColor = themeColor, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (onClear != null) {
                HeaderTextButton(label = "✕", focused = clearFocused, themeColor = themeColor, onClick = onClear)
                if (onPlus != null || onGear != null) Spacer(Modifier.width(8.dp))
            }
            if (onPlus != null) {
                HeaderTextButton(label = "+", focused = plusFocused, themeColor = themeColor, onClick = onPlus)
                if (onGear != null) Spacer(Modifier.width(8.dp))
            }
            if (onGear != null) {
                HeaderIconButton(
                    iconRes = com.r1.launcher.R.drawable.ic_settings,
                    focused = gearFocused,
                    themeColor = themeColor,
                    onClick = onGear,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (titleIconRes != 0) {
                Image(
                    painter = painterResource(titleIconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(themeColor),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = themeColor,
                fontSize = 32.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Shared compact / floating row body. */
@Composable
private fun HeaderRow(
    backFocused: Boolean,
    onBack: () -> Unit,
    themeColor: Color,
    subtitle: String?,
    trailingContent: (@Composable RowScope.() -> Unit)?,
    plusFocused: Boolean,
    onPlus: (() -> Unit)?,
    gearFocused: Boolean,
    onGear: (() -> Unit)?,
    clearFocused: Boolean = false,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        HeaderBackPill(focused = backFocused, themeColor = themeColor, onClick = onBack)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = themeColor,
                fontSize = 16.sp,
                fontFamily = type.appCard.fontFamily,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (trailingContent != null) {
            trailingContent()
        }
        if (onClear != null) {
            Spacer(Modifier.width(8.dp))
            HeaderTextButton(label = "✕", focused = clearFocused, themeColor = themeColor, onClick = onClear)
        }
        if (onPlus != null) {
            Spacer(Modifier.width(8.dp))
            HeaderTextButton(label = "+", focused = plusFocused, themeColor = themeColor, onClick = onPlus)
        }
        if (onGear != null) {
            Spacer(Modifier.width(8.dp))
            HeaderIconButton(
                iconRes = com.r1.launcher.R.drawable.ic_settings,
                focused = gearFocused,
                themeColor = themeColor,
                onClick = onGear,
            )
        }
    }
}

/**
 * Standalone themed back pill — same shape and focus behavior as the one
 * embedded in [AppPageHeader]. Use this when the panel can't host a full header
 * (e.g. an overlay corner on the recording screen, or a redirect splash).
 */
@Composable
fun PageBackPill(
    focused: Boolean,
    themeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeaderBackPill(focused = focused, themeColor = themeColor, onClick = onClick, modifier = modifier)
}

@Composable
private fun HeaderBackPill(
    focused: Boolean,
    themeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current
    // Borderless back pill — relies on themed text against the topbar gradient.
    // Focus state still flips to a solid orange tile so wheel navigation reads.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(if (focused) FOCUS_HIGHLIGHT else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "< back",
            color = if (focused) Color.Black else themeColor,
            fontSize = 22.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HeaderIconButton(iconRes: Int, focused: Boolean, themeColor: Color, onClick: () -> Unit) {
    val bg = if (focused) FOCUS_HIGHLIGHT else Color.Transparent
    val tint = if (focused) Color.Black else themeColor
    // Hard-cornered focus highlight to match the rest of the pixel idiom.
    Box(
        modifier = Modifier
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HeaderTextButton(label: String, focused: Boolean, themeColor: Color, onClick: () -> Unit) {
    val type = LocalR1Type.current
    val bg = if (focused) FOCUS_HIGHLIGHT else Color.Transparent
    val color = if (focused) Color.Black else themeColor
    // Hard-cornered focus highlight.
    Box(
        modifier = Modifier
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 26.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Section divider inside a settings list. Uppercase themed label with the same
 * indent as the surrounding rows. Non-focusable; used to group adjacent
 * [SettingsRow]s by purpose.
 */
@Composable
fun SectionHeader(
    label: String,
    themeColor: Color = DEFAULT_THEME,
    modifier: Modifier = Modifier,
) {
    val type = LocalR1Type.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 0.dp, start = 12.dp, end = 12.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = themeColor,
            fontSize = 18.sp,
            fontFamily = type.appCard.fontFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Per-app theme colors — match the launch-tile palette in `AppsPanel.kt` so the
 * top-bar reads as the same brand as the card the user just tapped. Sub-panels
 * inherit their parent app's color.
 */
object AppThemes {
    val Messages = Color(0xFFFF003C)   // bright red
    val OpenClaw = Color(0xFFFF2A9D)   // neon pink
    val Terminal = Color(0xFF00E5FF)   // cyan / light blue
    val Meetings = Color(0xFF00FF38)   // lime green
    val Settings = Color(0xFFFFD600)   // sunshine yellow
    val Hermes = Color(0xFFFFB300)     // warm amber
    val Camera = Color(0xFF00C2FF)     // electric blue — the camera/gallery family
    val Testing = Color(0xFF9B5DE5)    // violet — unused elsewhere in the palette
    val Translator = Color(0xFF14B8A6) // teal — distinct from Hermes amber & OpenClaw pink
}
