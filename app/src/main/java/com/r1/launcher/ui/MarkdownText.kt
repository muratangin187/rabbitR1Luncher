package com.r1.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

// Hoisted so the streaming bubble — which recomposes on every token — doesn't
// recompile them per delta.
private val CODE_BLOCK_RE = Regex("```([\\s\\S]*?)```")
private val INLINE_CODE_RE = Regex("`([^`\n]+)`")
private val BLOCKQUOTE_RE = Regex("(?m)^[ \t]*>[ \t]?")
private val IMAGE_RE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val HRULE_RE = Regex("(?m)^[ \t]*([-*_])(?:[ \t]*\\1){2,}[ \t]*$")
private val TABLE_ROW_RE = Regex("(?m)^\\s*\\|.*\\|\\s*$")

/**
 * Markdown for chat bubbles, with the launcher's hard-won workarounds applied.
 *
 * `multiplatform-markdown-renderer` is pinned at 0.24.0 against Compose BOM
 * 2024.10.01, and several of its block renderers call value-class overloads
 * that don't exist in compose.ui 1.7.x. Those are hard crashes, not visual
 * glitches, so the constructs that reach them are rewritten into plain text
 * before the library ever sees them:
 *
 *  - blockquotes  → `MarkdownBlockQuote` calls a missing `drawLine` overload
 *  - horizontal rules → same `drawLine` path
 *  - tables       → unsupported by the renderer and unreadable at 480px anyway
 *  - images       → resolved by the app as real bubbles, never inline
 *
 * Fenced code is unwrapped to bare text and inline code is promoted to bold,
 * matching what the existing OpenClaw chat panel does, so the look is
 * consistent across the launcher's chat surfaces.
 */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val cleaned = remember(text) { sanitizeMarkdown(text) }

    // A bubble that ends up with no markup at all is far cheaper as a plain
    // Text, and the streaming bubble hits this path for most of its life.
    if (remember(cleaned) { !looksLikeMarkdown(cleaned) }) {
        Text(text = cleaned, style = style, color = color, modifier = modifier)
        return
    }

    val base = style.copy(color = color)
    Markdown(
        content = cleaned,
        colors = markdownColor(
            text = color,
            codeText = accent,
            codeBackground = Color.Transparent,
            linkText = accent,
        ),
        typography = markdownTypography(
            text = base,
            code = base,
            paragraph = base,
            quote = base,
            list = base,
            ordered = base,
            bullet = base,
            h1 = base.copy(fontSize = style.fontSize * 1.35f, fontWeight = FontWeight.Bold),
            h2 = base.copy(fontSize = style.fontSize * 1.25f, fontWeight = FontWeight.Bold),
            h3 = base.copy(fontSize = style.fontSize * 1.15f, fontWeight = FontWeight.Bold),
            h4 = base.copy(fontWeight = FontWeight.Bold),
            h5 = base.copy(fontWeight = FontWeight.Bold),
            h6 = base.copy(fontWeight = FontWeight.Bold),
        ),
        modifier = modifier,
    )
}

/** Visible for the TTS path, which wants prose without markup read aloud. */
fun sanitizeMarkdown(raw: String): String = raw
    .replace(IMAGE_RE, "")
    .replace(CODE_BLOCK_RE) { m ->
        val inner = m.groupValues[1].trim('\n')
        val nl = inner.indexOf('\n')
        // Drop the first line only when it's a bare language tag, so
        // "```js\nfoo" doesn't lose the "foo".
        if (nl > 0 && inner.substring(0, nl).all {
                it.isLetterOrDigit() || it == '+' || it == '-' || it == '_'
            }
        ) inner.substring(nl + 1).trim('\n') else inner
    }
    .replace(INLINE_CODE_RE, "**$1**")
    .replace(BLOCKQUOTE_RE, "")
    .replace(HRULE_RE, "")
    // Table pipes render as a wall of vertical bars at this width; strip the
    // delimiters and keep the cells as a line of text.
    .replace(TABLE_ROW_RE) { m ->
        val cells = m.value.trim().trim('|').split('|').map { it.trim() }
        if (cells.all { it.isEmpty() || it.all { c -> c == '-' || c == ':' } }) ""
        else cells.filter { it.isNotEmpty() }.joinToString("  ·  ")
    }
    .trim()

/** Plain prose for speech: strip the remaining emphasis markers too. */
fun markdownToSpeech(raw: String): String = sanitizeMarkdown(raw)
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("\n{2,}"), ". ")
    .replace('\n', ' ')
    .replace(Regex("\\s{2,}"), " ")
    .trim()

private fun looksLikeMarkdown(s: String): Boolean =
    s.contains("**") || s.contains("__") ||
        s.contains("\n#") || s.startsWith("#") ||
        Regex("(?m)^\\s*[-*+]\\s+\\S").containsMatchIn(s) ||
        Regex("(?m)^\\s*\\d+\\.\\s+\\S").containsMatchIn(s) ||
        s.contains("](")
