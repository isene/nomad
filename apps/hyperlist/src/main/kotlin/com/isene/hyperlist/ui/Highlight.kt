package com.isene.hyperlist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import uniffi.fe2o3_mobile_core.LineSpans
import uniffi.fe2o3_mobile_core.TokenRole

// String-annotation tag for reference spans, so a tap on a <reference> can be
// resolved back to its target text by the screen's tap handler.
const val REF_TAG = "hl_ref"

private data class RoleStyle(
    val color: Color? = null,
    val background: Color? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

// Colours from the canonical HyperList scheme (skill color-scheme.md). Dark
// palette uses the bright TUI colours with blue lightened for legibility on
// black; light palette uses the darker print colours.
private val DARK: Map<TokenRole, RoleStyle> = mapOf(
    TokenRole.PROPERTY to RoleStyle(color = Color(0xFFFF6B6B)),
    TokenRole.MULTI_MARKER to RoleStyle(color = Color(0xFFFF6B6B)),
    TokenRole.CHANGE_MARKUP to RoleStyle(color = Color(0xFFFF6B6B)),
    TokenRole.OPERATOR to RoleStyle(color = Color(0xFF7FA8FF)),
    TokenRole.STATE_TRANSITION to RoleStyle(color = Color(0xFF7FA8FF)),
    TokenRole.QUALIFIER to RoleStyle(color = Color(0xFF4CD964)),
    TokenRole.CHECKBOX to RoleStyle(color = Color(0xFF4CD964)),
    TokenRole.SEMICOLON to RoleStyle(color = Color(0xFF4CD964)),
    TokenRole.REFERENCE to RoleStyle(color = Color(0xFFE07BFF)),
    TokenRole.IDENTIFIER to RoleStyle(color = Color(0xFFE07BFF)),
    TokenRole.KEYWORD to RoleStyle(color = Color(0xFFE07BFF)),
    TokenRole.COMMENT to RoleStyle(color = Color(0xFF4DD9D9)),
    TokenRole.QUOTE to RoleStyle(color = Color(0xFF4DD9D9)),
    TokenRole.SUBSTITUTION to RoleStyle(color = Color(0xFFAFD75F)),
    TokenRole.HASHTAG to RoleStyle(color = Color(0xFFFFA94D)),
    TokenRole.TODO to RoleStyle(color = Color(0xFF000000), background = Color(0xFFFFEB3B), bold = true),
    TokenRole.BOLD to RoleStyle(bold = true),
    TokenRole.ITALIC to RoleStyle(italic = true),
    TokenRole.UNDERLINE to RoleStyle(underline = true),
    TokenRole.LITERAL to RoleStyle(color = Color(0xFF9E9E9E)),
    TokenRole.FADED to RoleStyle(color = Color(0xFF9E9E9E)),
)

private val LIGHT: Map<TokenRole, RoleStyle> = mapOf(
    TokenRole.PROPERTY to RoleStyle(color = Color(0xFFCC0000)),
    TokenRole.MULTI_MARKER to RoleStyle(color = Color(0xFFCC0000)),
    TokenRole.CHANGE_MARKUP to RoleStyle(color = Color(0xFFCC0000)),
    TokenRole.OPERATOR to RoleStyle(color = Color(0xFF0000CC)),
    TokenRole.STATE_TRANSITION to RoleStyle(color = Color(0xFF0000CC)),
    TokenRole.QUALIFIER to RoleStyle(color = Color(0xFF008000)),
    TokenRole.CHECKBOX to RoleStyle(color = Color(0xFF008000)),
    TokenRole.SEMICOLON to RoleStyle(color = Color(0xFF008000)),
    TokenRole.REFERENCE to RoleStyle(color = Color(0xFFAA00AA)),
    TokenRole.IDENTIFIER to RoleStyle(color = Color(0xFFAA00AA)),
    TokenRole.KEYWORD to RoleStyle(color = Color(0xFFAA00AA)),
    TokenRole.COMMENT to RoleStyle(color = Color(0xFF008B8B)),
    TokenRole.QUOTE to RoleStyle(color = Color(0xFF008B8B)),
    TokenRole.SUBSTITUTION to RoleStyle(color = Color(0xFF6B8E23)),
    TokenRole.HASHTAG to RoleStyle(color = Color(0xFFCC5500)),
    TokenRole.TODO to RoleStyle(color = Color(0xFF000000), background = Color(0xFFFFD600), bold = true),
    TokenRole.BOLD to RoleStyle(bold = true),
    TokenRole.ITALIC to RoleStyle(italic = true),
    TokenRole.UNDERLINE to RoleStyle(underline = true),
    TokenRole.LITERAL to RoleStyle(color = Color(0xFF757575)),
    TokenRole.FADED to RoleStyle(color = Color(0xFF757575)),
)

/**
 * Build a styled line from the Rust highlighter spans. Span texts concatenate
 * to the original line, so we just append each with its role's style.
 * Reference spans get a string annotation so a tap can resolve the target.
 */
fun spansToAnnotatedString(line: LineSpans, dark: Boolean): AnnotatedString {
    val palette = if (dark) DARK else LIGHT
    return buildAnnotatedString {
        for (span in line.spans) {
            val rs = palette[span.role]
            if (span.role == TokenRole.REFERENCE) {
                // strip < > / << >> and trim for the annotation payload
                val target = span.text.trim('<', '>', ' ')
                pushStringAnnotation(REF_TAG, target)
            }
            if (rs == null) {
                append(span.text)
            } else {
                withStyle(
                    SpanStyle(
                        color = rs.color ?: Color.Unspecified,
                        background = rs.background ?: Color.Unspecified,
                        fontWeight = if (rs.bold) FontWeight.Bold else null,
                        fontStyle = if (rs.italic) FontStyle.Italic else null,
                        textDecoration = if (rs.underline) TextDecoration.Underline else null,
                    ),
                ) { append(span.text) }
            }
            if (span.role == TokenRole.REFERENCE) {
                pop()
            }
        }
    }
}
