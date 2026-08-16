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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel

/**
 * Scratch panel used to exercise the edit → build → install → verify loop.
 * Deliberately self-contained: one state field, one action, no host calls.
 * Delete the file and its `AppEntry.Testing` / `Panel.TESTING` branches to
 * remove it entirely.
 *
 * Focus model matches the rest of the launcher — the scroll wheel moves
 * [LauncherState.testingFocus] between 0 (back pill) and 1 (the button), and a
 * wheel press activates whichever is focused. Touch works independently.
 */
@Composable
fun TestingPanel(
    state: LauncherState,
    onBack: () -> Unit,
    onTap: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.panel == Panel.TESTING,
        enter = fadeIn(tween(ANIM_OPEN_MS)) +
            slideInVertically(tween(ANIM_OPEN_MS)) { it },
        exit = fadeOut(tween(ANIM_CLOSE_MS)) +
            slideOutVertically(tween(ANIM_CLOSE_MS)) { it },
    ) {
        val type = LocalR1Type.current
        val accent = AppThemes.Testing
        val buttonFocused = state.testingFocus == 1

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppPageHeader(
                    title = "testing",
                    backFocused = state.testingFocus == 0,
                    onBack = onBack,
                    themeColor = accent,
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "TESTING",
                        style = type.clock,
                        fontSize = 46.sp,
                        color = accent,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = "build loop works",
                        style = type.appCard,
                        fontSize = 16.sp,
                        color = Color(0xFF8A8A8A),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                    )

                    // The tile is the focus target for the wheel AND independently
                    // clickable, so both input paths land on the same action.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusAnim(buttonFocused)
                            .background(if (buttonFocused) accent else Color(0xFF141414))
                            .border(2.dp, accent)
                            .clickable { onTap() }
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "tap me",
                            style = type.appCard,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (buttonFocused) Color.Black else accent,
                        )
                    }

                    Text(
                        text = "taps: ${state.testingCount}",
                        style = type.clock,
                        fontSize = 30.sp,
                        color = Color.White,
                        modifier = Modifier.padding(top = 26.dp),
                    )
                }
            }
        }
    }
}
