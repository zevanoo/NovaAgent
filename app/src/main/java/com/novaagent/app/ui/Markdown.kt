package com.novaagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.novaagent.app.ui.theme.monoStyle

/**
 * Minimal markdown rendering: fenced code blocks as separate surfaces, and inline
 * bold / italic / code spans inside prose. Tables and images are deliberately left
 * as text — chat answers rarely need them and a real parser is a heavy dependency.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { splitFences(text) }
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Code -> CodeBlock(block.language, block.body)
                is Block.Prose -> SelectionContainer {
                    Text(
                        annotated(block.body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private sealed interface Block {
    data class Prose(val body: String) : Block
    data class Code(val language: String, val body: String) : Block
}

private fun splitFences(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val prose = StringBuilder()
    var inCode = false
    var language = ""
    val code = StringBuilder()

    text.lines().forEach { line ->
        when {
            line.startsWith("```") -> {
                if (inCode) {
                    if (prose.isNotBlank()) out.add(Block.Prose(prose.toString().trim()))
                    prose.clear()
                    out.add(Block.Code(language, code.toString().trimEnd()))
                    code.clear()
                    inCode = false
                } else {
                    if (prose.isNotBlank()) out.add(Block.Prose(prose.toString().trim()))
                    prose.clear()
                    language = line.removePrefix("```").trim()
                    inCode = true
                }
            }
            inCode -> code.appendLine(line)
            else -> prose.appendLine(line)
        }
    }
    if (inCode && code.isNotBlank()) out.add(Block.Code(language, code.toString().trimEnd()))
    if (prose.isNotBlank()) out.add(Block.Prose(prose.toString().trim()))
    return if (out.isEmpty()) listOf(Block.Prose(text)) else out
}

/**
 * Inline formatting in one pass: `code`, **bold**, *italic*. A tiny scanner beats
 * a regex chain here because nested delimiters otherwise swallow each other.
 */
private fun annotated(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        val match = INLINE.find(rest)
        if (match == null) {
            append(rest)
            break
        }
        append(rest.substring(0, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(token.trim('`'))
            }
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(token.trim('*'))
            }
            else -> withStyle(
                SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            ) { append(token.trim('*')) }
        }
        i += match.range.last + 1
    }
}

private val INLINE = Regex("`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*")

@Composable
private fun CodeBlock(language: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (language.isNotBlank()) {
            Text(
                language.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        SelectionContainer {
            Text(
                body,
                style = monoStyle(12),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
