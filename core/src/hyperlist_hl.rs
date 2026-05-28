// HyperList syntax highlighting.
//
// Faithful port of `highlight::highlight_hyperlist` from
// /home/geir/Main/G/GIT-isene/highlight/src/source.rs (the canonical Fe2O3
// highlighter, which emits ANSI and depends on crust). Here we emit
// structured, NON-OVERLAPPING (text, role) spans instead of ANSI, so:
//   - the crate stays free of any TUI/terminal dependency, and
//   - Kotlin can build a Compose AnnotatedString by simply appending each
//     span's text with the style mapped from its role (no byte/char offset
//     math, which would otherwise have to bridge UTF-8 <-> UTF-16).
//
// The concatenation of a line's span texts equals the input line exactly.
//
// Colour mapping lives on the Kotlin side (light/dark aware); this module
// only classifies. Roles mirror hyperlist.vim / the TUI:
//   Property/MultiMarker/ChangeMarkup  -> red
//   Operator/StateTransition           -> blue
//   Qualifier/Checkbox/Semicolon       -> green
//   Reference/Identifier/Keyword       -> magenta
//   Comment/Quote                      -> cyan
//   Substitution                       -> yellow-green (157)
//   Hashtag                            -> 184
//   Todo                               -> black on yellow
//   Bold/Italic/Underline              -> style only (default colour)
//   Literal/Faded                      -> dim / no syntax

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TokenRole {
    Normal,
    Faded,
    Identifier,
    Property,
    Operator,
    StateTransition,
    Semicolon,
    Checkbox,
    Qualifier,
    Substitution,
    Reference,
    Comment,
    Quote,
    ChangeMarkup,
    Hashtag,
    Keyword,
    Todo,
    MultiMarker,
    Bold,
    Italic,
    Underline,
    Literal,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HlSpan {
    pub text: String,
    pub role: TokenRole,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LineSpans {
    pub spans: Vec<HlSpan>,
}

/// Highlight every line of a document. `lines` are the item BODIES (indent
/// already stripped into the doc model; the structured editor renders indent
/// as padding, not literal tabs). Literal-block state (a lone `\`) is tracked
/// across lines, so this must be called on the whole visible document, not
/// line-by-line in isolation.
#[uniffi::export]
pub fn highlight_doc(lines: Vec<String>) -> Vec<LineSpans> {
    let mut out = Vec::with_capacity(lines.len());
    let mut in_literal = false;
    for line in &lines {
        if line == "\\" {
            // Literal toggle marker. The `\` itself renders italic.
            out.push(LineSpans {
                spans: vec![HlSpan { text: "\\".into(), role: TokenRole::Italic }],
            });
            in_literal = !in_literal;
            continue;
        }
        if in_literal {
            out.push(LineSpans {
                spans: vec![HlSpan { text: line.clone(), role: TokenRole::Literal }],
            });
            continue;
        }
        out.push(LineSpans { spans: hl_line(line) });
    }
    out
}

/// Highlight a single body line, given whether it sits inside a literal block.
/// Returns the spans plus whether this line is a literal-block toggle (a lone
/// `\`), so an incremental caller can keep its own `in_literal` state.
#[uniffi::export]
pub fn highlight_line(line: String, in_literal: bool) -> LineSpans {
    if line == "\\" {
        return LineSpans {
            spans: vec![HlSpan { text: "\\".into(), role: TokenRole::Italic }],
        };
    }
    if in_literal {
        return LineSpans {
            spans: vec![HlSpan { text: line, role: TokenRole::Literal }],
        };
    }
    LineSpans { spans: hl_line(&line) }
}

/// True iff the body is a lone `\` (toggles literal-block state).
#[uniffi::export]
pub fn is_literal_toggle(line: String) -> bool {
    line == "\\"
}

// -------------------- span builder --------------------

struct SpanBuf {
    spans: Vec<HlSpan>,
    pending: String, // accumulated Normal chars not yet flushed
}

impl SpanBuf {
    fn new() -> Self {
        SpanBuf { spans: Vec::new(), pending: String::new() }
    }
    fn norm(&mut self, c: char) {
        self.pending.push(c);
    }
    fn flush(&mut self) {
        if !self.pending.is_empty() {
            self.spans.push(HlSpan {
                text: std::mem::take(&mut self.pending),
                role: TokenRole::Normal,
            });
        }
    }
    fn push(&mut self, text: String, role: TokenRole) {
        self.flush();
        if !text.is_empty() {
            self.spans.push(HlSpan { text, role });
        }
    }
    fn finish(mut self) -> Vec<HlSpan> {
        self.flush();
        self.spans
    }
}

// -------------------- the line walk --------------------

fn hl_line(body: &str) -> Vec<HlSpan> {
    // vim modeline.
    if body.starts_with("vim:") {
        return vec![HlSpan { text: body.to_string(), role: TokenRole::Faded }];
    }

    let mut sb = SpanBuf::new();

    // Multi-line indicator `+ ` at start of body.
    let body = if let Some(rest) = body.strip_prefix("+ ") {
        sb.push("+ ".into(), TokenRole::MultiMarker);
        rest
    } else {
        body
    };
    if body.is_empty() {
        return sb.finish();
    }

    let work: Vec<char> = body.chars().collect();
    let len = work.len();
    let mut i = 0;

    // Identifier `[0-9.]+ ` (at least one digit), space-terminated.
    {
        let mut j = 0;
        while j < len && (work[j].is_ascii_digit() || work[j] == '.') {
            j += 1;
        }
        if j > 0 && j < len && work[j] == ' ' && work[..j].iter().any(|c| c.is_ascii_digit()) {
            let ident: String = work[..j + 1].iter().collect();
            sb.push(ident, TokenRole::Identifier);
            i = j + 1;
        }
    }

    // Header (Property / Operator) at the start of each `;`-segment, plus the
    // S:/T:/|/ markers.
    let mut seg_start = i;
    detect_segment_header(&mut sb, &work, &mut i, seg_start);

    while i < len {
        let ch = work[i];

        if ch == ';' {
            sb.push(";".into(), TokenRole::Semicolon);
            i += 1;
            seg_start = i;
            detect_segment_header(&mut sb, &work, &mut i, seg_start);
            continue;
        }

        // Checkbox `[ ]` `[_]` `[-]` `[X]` `[x]` `[O]` `[o]`.
        if ch == '[' && i + 2 < len && work[i + 2] == ']'
            && matches!(work[i + 1], 'X' | 'x' | 'O' | 'o' | '-' | ' ' | '_')
        {
            let s: String = work[i..i + 3].iter().collect();
            sb.push(s, TokenRole::Checkbox);
            i += 3;
            continue;
        }
        // Qualifier `[…]`.
        if ch == '[' {
            let start = i;
            i += 1;
            while i < len && work[i] != ']' {
                i += 1;
            }
            if i < len {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            sb.push(s, TokenRole::Qualifier);
            continue;
        }
        // Substitution `{…}`.
        if ch == '{' {
            let start = i;
            i += 1;
            while i < len && work[i] != '}' {
                i += 1;
            }
            if i < len {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            sb.push(s, TokenRole::Substitution);
            continue;
        }
        // Reference `<…>` / `<<…>>`.
        if ch == '<' {
            let start = i;
            i += 1;
            if i < len && work[i] == '<' {
                i += 1;
            }
            while i < len && work[i] != '>' {
                i += 1;
            }
            if i < len {
                i += 1;
            }
            if i < len && work[i] == '>' {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            if s.chars().count() >= 3 {
                sb.push(s, TokenRole::Reference);
            } else {
                for &c in &work[start..i] {
                    sb.norm(c);
                }
            }
            continue;
        }
        // Comment `(…)` (nested-aware), inner refs/hashtags/TODO keep colour.
        if ch == '(' {
            let start = i;
            i += 1;
            let mut depth = 1;
            while i < len && depth > 0 {
                if work[i] == '(' {
                    depth += 1;
                } else if work[i] == ')' {
                    depth -= 1;
                }
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            emit_inner(&mut sb, &s, TokenRole::Comment);
            continue;
        }
        // Quote `"…"`, inner refs/hashtags/TODO keep colour.
        if ch == '"' {
            let start = i;
            i += 1;
            while i < len && work[i] != '"' {
                i += 1;
            }
            if i < len {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            emit_inner(&mut sb, &s, TokenRole::Quote);
            continue;
        }
        // Change markup `##…` to next whitespace.
        if ch == '#' && i + 1 < len && work[i + 1] == '#' {
            let start = i;
            i += 2;
            while i < len && !work[i].is_whitespace() {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            sb.push(s, TokenRole::ChangeMarkup);
            continue;
        }
        // Hashtag `#tag`.
        if ch == '#' && i + 1 < len && hl_hash_char(work[i + 1]) {
            let start = i;
            i += 1;
            while i < len && hl_hash_char(work[i]) {
                i += 1;
            }
            let s: String = work[start..i].iter().collect();
            sb.push(s, TokenRole::Hashtag);
            continue;
        }
        // Reserved keywords END / SKIP, standalone.
        if ch == 'E' || ch == 'S' {
            let start = i;
            while i < len && work[i].is_ascii_uppercase() {
                i += 1;
            }
            let word: String = work[start..i].iter().collect();
            if matches!(word.as_str(), "END" | "SKIP") && (i >= len || !work[i].is_alphanumeric()) {
                sb.push(word, TokenRole::Keyword);
                continue;
            }
            i = start;
        }
        // TODO / FIXME.
        if ch == 'T' || ch == 'F' {
            let start = i;
            while i < len && work[i].is_ascii_uppercase() {
                i += 1;
            }
            let word: String = work[start..i].iter().collect();
            if matches!(word.as_str(), "TODO" | "FIXME") && (i >= len || !work[i].is_alphanumeric()) {
                sb.push(word, TokenRole::Todo);
                continue;
            }
            i = start;
        }
        // `*bold*` — preceded by space/tab/start, closing `*` followed by space/EOL.
        if ch == '*'
            && (i == 0 || work[i - 1] == ' ' || work[i - 1] == '\t')
            && i + 1 < len
            && work[i + 1] != ' '
            && work[i + 1] != '*'
        {
            if let Some(rel) = work[i + 1..].iter().position(|&c| c == '*') {
                let close = i + 1 + rel;
                let after_ok = close + 1 >= len || work[close + 1] == ' ';
                if after_ok {
                    let s: String = work[i..=close].iter().collect();
                    sb.push(s, TokenRole::Bold);
                    i = close + 1;
                    continue;
                }
            }
        }
        // `/italic/`.
        if ch == '/'
            && (i == 0 || work[i - 1] == ' ' || work[i - 1] == '\t')
            && i + 1 < len
            && work[i + 1] != ' '
            && work[i + 1] != '/'
        {
            if let Some(rel) = work[i + 1..].iter().position(|&c| c == '/') {
                let close = i + 1 + rel;
                let after_ok = close + 1 >= len || work[close + 1] == ' ';
                if after_ok {
                    let s: String = work[i..=close].iter().collect();
                    sb.push(s, TokenRole::Italic);
                    i = close + 1;
                    continue;
                }
            }
        }
        // `_underline_`.
        if ch == '_'
            && (i == 0 || work[i - 1] == ' ' || work[i - 1] == '\t')
            && i + 1 < len
            && work[i + 1] != ' '
            && work[i + 1] != '_'
        {
            if let Some(rel) = work[i + 1..].iter().position(|&c| c == '_') {
                let close = i + 1 + rel;
                let after_ok = close + 1 >= len || work[close + 1] == ' ';
                if after_ok {
                    let s: String = work[i..=close].iter().collect();
                    sb.push(s, TokenRole::Underline);
                    i = close + 1;
                    continue;
                }
            }
        }

        sb.norm(ch);
        i += 1;
    }

    sb.finish()
}

/// Detect a Property/Operator header (or S:/T:/|//  marker) at the start of a
/// `;`-segment beginning at `from`, and emit + advance `*i` past it.
fn detect_segment_header(sb: &mut SpanBuf, work: &[char], i: &mut usize, from: usize) {
    let len = work.len();
    // End of this segment = next `;` or EOL.
    let mut seg_end = from;
    while seg_end < len && work[seg_end] != ';' {
        seg_end += 1;
    }
    if let Some((hdr_end, is_op)) = detect_hl_header(&work[from..seg_end]) {
        let hdr: String = work[from..from + hdr_end].iter().collect();
        let role = if is_op { TokenRole::Operator } else { TokenRole::Property };
        sb.push(hdr, role);
        *i = from + hdr_end;
    } else if work[from..seg_end].starts_with(&['S', ':', ' '])
        || work[from..seg_end].starts_with(&['T', ':', ' '])
    {
        let mark: String = work[from..from + 3].iter().collect();
        sb.push(mark, TokenRole::StateTransition);
        *i = from + 3;
    } else if from + 2 <= len && work[from] == '|' && work[from + 1] == ' ' {
        sb.push("| ".into(), TokenRole::StateTransition);
        *i = from + 2;
    } else if from + 2 <= len && from == 0 && work[from] == '/' && work[from + 1] == ' ' {
        sb.push("/ ".into(), TokenRole::StateTransition);
        *i = from + 2;
    }
}

/// Emit a delimited region (comment/quote) in `outer` while keeping inner
/// References, Hashtags, and TODO/FIXME in their own roles. Produces a
/// contiguous, non-overlapping span sequence covering `full` exactly.
fn emit_inner(sb: &mut SpanBuf, full: &str, outer: TokenRole) {
    let chars: Vec<char> = full.chars().collect();
    let n = chars.len();
    let mut i = 0;
    let mut buf = String::new();
    macro_rules! flush_outer {
        () => {{
            if !buf.is_empty() {
                sb.push(std::mem::take(&mut buf), outer);
            }
        }};
    }
    while i < n {
        let c = chars[i];
        if c == '<' {
            let start = i;
            i += 1;
            if i < n && chars[i] == '<' {
                i += 1;
            }
            while i < n && chars[i] != '>' {
                i += 1;
            }
            if i < n {
                i += 1;
            }
            if i < n && chars[i] == '>' {
                i += 1;
            }
            if i - start >= 3 {
                flush_outer!();
                let s: String = chars[start..i].iter().collect();
                sb.push(s, TokenRole::Reference);
            } else {
                for &x in &chars[start..i] {
                    buf.push(x);
                }
            }
            continue;
        }
        if c == '#' && i + 1 < n && hl_hash_char(chars[i + 1]) {
            let start = i;
            i += 1;
            while i < n && hl_hash_char(chars[i]) {
                i += 1;
            }
            flush_outer!();
            let s: String = chars[start..i].iter().collect();
            sb.push(s, TokenRole::Hashtag);
            continue;
        }
        if c == 'T' || c == 'F' {
            let start = i;
            while i < n && chars[i].is_ascii_uppercase() {
                i += 1;
            }
            let word: String = chars[start..i].iter().collect();
            if matches!(word.as_str(), "TODO" | "FIXME") && (i >= n || !chars[i].is_alphanumeric()) {
                flush_outer!();
                sb.push(word, TokenRole::Todo);
                continue;
            }
            i = start;
        }
        buf.push(c);
        i += 1;
    }
    flush_outer!();
}

/// Char class for hashtag content (matches vim's regex).
fn hl_hash_char(c: char) -> bool {
    c.is_ascii_alphanumeric()
        || matches!(c, '.' | ':' | '/' | '_' | '&' | '?' | '%' | '=' | '+' | '-' | '*')
        || matches!(
            c,
            'æ' | 'ø' | 'å' | 'Æ' | 'Ø' | 'Å' | 'á' | 'é' | 'ó' | 'ú' | 'ã' | 'õ' | 'â' | 'ê'
                | 'ô' | 'ç' | 'à' | 'Á' | 'É' | 'Ó' | 'Ú' | 'Ã' | 'Õ' | 'Â' | 'Ê' | 'Ô' | 'Ç'
                | 'À' | 'ü'
        )
}

/// Detect a Property (mixed case `Name: `) or Operator (ALL-CAPS `OP: `) at
/// the start of `work`. Returns (end_index_past_colon_space, is_operator).
/// The `: ` must not be inside `[]`, `()`, `{}`, `<>`, or `""`.
fn detect_hl_header(work: &[char]) -> Option<(usize, bool)> {
    let mut depth_sq = 0i32;
    let mut depth_pa = 0i32;
    let mut depth_br = 0i32;
    let mut depth_an = 0i32;
    let mut in_quote = false;
    for i in 0..work.len() {
        let c = work[i];
        if in_quote {
            if c == '"' {
                in_quote = false;
            }
            continue;
        }
        match c {
            '"' => in_quote = true,
            '[' => depth_sq += 1,
            ']' => depth_sq -= 1,
            '(' => depth_pa += 1,
            ')' => depth_pa -= 1,
            '{' => depth_br += 1,
            '}' => depth_br -= 1,
            '<' => depth_an += 1,
            '>' => depth_an -= 1,
            ':' => {
                if depth_sq <= 0 && depth_pa <= 0 && depth_br <= 0 && depth_an <= 0 {
                    let next_is_space = i + 1 >= work.len() || work[i + 1] == ' ';
                    if next_is_space {
                        let end = if i + 1 < work.len() { i + 2 } else { i + 1 };
                        let prefix: String = work[..i].iter().collect();
                        let trimmed = prefix.trim();
                        if trimmed.is_empty() {
                            return None;
                        }
                        let has_letter = trimmed.chars().any(|c| c.is_ascii_alphabetic());
                        let all_upper_or_punct = trimmed.chars().all(|c| {
                            c.is_ascii_uppercase()
                                || matches!(c, ' ' | '_' | '-' | '(' | ')' | '/')
                        });
                        let is_op = has_letter && all_upper_or_punct;
                        return Some((end, is_op));
                    }
                }
            }
            _ => {}
        }
    }
    None
}

// -------------------- tests --------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Find the role applied to the first occurrence of `needle` in the line.
    fn role_of(line: &str, needle: &str) -> Option<TokenRole> {
        let spans = hl_line(line);
        for s in &spans {
            if s.text.contains(needle) {
                return Some(s.role);
            }
        }
        None
    }

    fn concat(line: &str) -> String {
        hl_line(line).iter().map(|s| s.text.clone()).collect()
    }

    #[test]
    fn spans_reconstruct_line() {
        for line in [
            "Check: The Property should be in red",
            "CHECK: The Operator should be in blue",
            "[? OK] Qualifier in green",
            "A #hashtag; And more",
            "1. numbering identifier",
            "+ multi-line indicator",
            "link <to nowhere>",
            "comment (with <link> inside)",
            "quote \"with <link> inside\"",
            "Markup *bold* /italics/ _underline_",
            "Kjøre Pål til skolen #lørdag",
        ] {
            assert_eq!(concat(line), line, "reconstruct failed for {line:?}");
        }
    }

    #[test]
    fn property_is_red() {
        assert_eq!(role_of("Check: foo", "Check: "), Some(TokenRole::Property));
    }

    #[test]
    fn operator_is_blue() {
        assert_eq!(role_of("CHECK: foo", "CHECK: "), Some(TokenRole::Operator));
        assert_eq!(role_of("AND: a", "AND: "), Some(TokenRole::Operator));
    }

    #[test]
    fn qualifier_and_checkbox_green() {
        assert_eq!(role_of("[? OK] q", "[? OK]"), Some(TokenRole::Qualifier));
        assert_eq!(role_of("[x] done", "[x]"), Some(TokenRole::Checkbox));
        assert_eq!(role_of("[ ] todo", "[ ]"), Some(TokenRole::Checkbox));
    }

    #[test]
    fn reference_and_identifier_magenta() {
        assert_eq!(role_of("see <target>", "<target>"), Some(TokenRole::Reference));
        assert_eq!(role_of("1.2 item", "1.2 "), Some(TokenRole::Identifier));
    }

    #[test]
    fn comment_quote_cyan_with_inner_ref() {
        // Inner ref inside a comment keeps Reference role.
        let spans = hl_line("text (a <ref> b)");
        let roles: Vec<_> = spans.iter().map(|s| s.role).collect();
        assert!(roles.contains(&TokenRole::Comment));
        assert!(roles.contains(&TokenRole::Reference));
        // And the comment text reconstructs.
        assert_eq!(concat("text (a <ref> b)"), "text (a <ref> b)");
    }

    #[test]
    fn substitution_hashtag() {
        assert_eq!(role_of("use {var} here", "{var}"), Some(TokenRole::Substitution));
        assert_eq!(role_of("a #tag b", "#tag"), Some(TokenRole::Hashtag));
    }

    #[test]
    fn keywords_and_todo() {
        assert_eq!(role_of("then END", "END"), Some(TokenRole::Keyword));
        assert_eq!(role_of("SKIP this", "SKIP"), Some(TokenRole::Keyword));
        assert_eq!(role_of("TODO fix it", "TODO"), Some(TokenRole::Todo));
        assert_eq!(role_of("FIXME later", "FIXME"), Some(TokenRole::Todo));
    }

    #[test]
    fn markup_styles() {
        assert_eq!(role_of("a *bold* b", "*bold*"), Some(TokenRole::Bold));
        assert_eq!(role_of("a /it/ b", "/it/"), Some(TokenRole::Italic));
        assert_eq!(role_of("a _un_ b", "_un_"), Some(TokenRole::Underline));
    }

    #[test]
    fn multimarker_and_semicolon_and_state() {
        assert_eq!(role_of("+ continues", "+ "), Some(TokenRole::MultiMarker));
        assert_eq!(role_of("a; b", ";"), Some(TokenRole::Semicolon));
        // `S: ` / `T: ` are caught by the Property/Operator header detector
        // first (prefix is all-caps -> Operator), matching the canonical
        // highlighter. Same blue colour. The dedicated StateTransition role
        // is produced by the bare `| ` and `/ ` markers.
        assert_eq!(role_of("S: a state", "S: "), Some(TokenRole::Operator));
        assert_eq!(role_of("| a state", "| "), Some(TokenRole::StateTransition));
    }

    #[test]
    fn change_markup_red() {
        assert_eq!(role_of("moved ##>", "##>"), Some(TokenRole::ChangeMarkup));
    }

    #[test]
    fn literal_block_toggles() {
        let out = highlight_doc(vec![
            "before".into(),
            "\\".into(),
            "[? OK] *bold* #tag".into(),
            "\\".into(),
            "after".into(),
        ]);
        // line 0: normal
        assert_eq!(out[0].spans[0].role, TokenRole::Normal);
        // line 1: the `\` toggle (italic)
        assert_eq!(out[1].spans[0].role, TokenRole::Italic);
        // line 2: inside literal block -> single Literal span, no syntax
        assert_eq!(out[2].spans.len(), 1);
        assert_eq!(out[2].spans[0].role, TokenRole::Literal);
        assert_eq!(out[2].spans[0].text, "[? OK] *bold* #tag");
        // line 4: back to normal highlighting
        assert!(out[4].spans.iter().all(|s| s.role == TokenRole::Normal));
    }

    #[test]
    fn semicolon_restarts_header_detection() {
        // Property in first segment, Operator after the `;`.
        let spans = hl_line("Name: x; AND: y");
        let roles: Vec<_> = spans.iter().map(|s| (s.text.as_str(), s.role)).collect();
        assert!(roles.iter().any(|(t, r)| *t == "Name: " && *r == TokenRole::Property));
        assert!(roles.iter().any(|(t, r)| *t == ";" && *r == TokenRole::Semicolon));
        // The header span after `;` includes the leading space (faithful to
        // the canonical walk): " AND: ".
        assert!(roles
            .iter()
            .any(|(t, r)| t.trim() == "AND:" && *r == TokenRole::Operator));
    }
}
