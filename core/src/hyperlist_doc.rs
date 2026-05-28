// HyperList document model: a flat list of indented lines.
//
// Unlike the tasks app's 2-level category/item model (`hyperlist` module),
// this represents an arbitrary-depth HyperList faithfully: each line carries
// its indent depth and its body text (indent stripped). Tabs are the indent
// unit; `*` is accepted on parse (hyperlist.vim allows it) and normalised to
// tabs on serialize. This round-trips tab-indented `.hl` files byte-exactly
// (modulo a guaranteed trailing newline).
//
// "Subtree" = a line plus all following lines of greater indent. Indent,
// outdent, move, and fold all operate on subtrees, matching scribe.

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HlLine {
    pub indent: u32,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct HlDoc {
    pub lines: Vec<HlLine>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct LineRange {
    pub start: u32,
    pub end: u32,
}

// -------------------- parse / serialize --------------------

#[uniffi::export]
pub fn parse_doc(text: String) -> HlDoc {
    let mut lines = Vec::new();
    // Split on '\n', preserving blank lines. Drop a single trailing empty
    // produced by a final newline so round-trips don't grow a blank line.
    let raw: Vec<&str> = text.split('\n').collect();
    let n = raw.len();
    for (idx, line) in raw.iter().enumerate() {
        if idx == n - 1 && line.is_empty() {
            // Trailing newline artifact; skip.
            continue;
        }
        let indent = line.chars().take_while(|c| *c == '\t' || *c == '*').count() as u32;
        let body: String = line.chars().skip(indent as usize).collect();
        lines.push(HlLine { indent, text: body });
    }
    HlDoc { lines }
}

#[uniffi::export]
pub fn serialize_doc(doc: HlDoc) -> String {
    let mut out = String::new();
    for line in &doc.lines {
        for _ in 0..line.indent {
            out.push('\t');
        }
        out.push_str(&line.text);
        out.push('\n');
    }
    out
}

// -------------------- structural helpers --------------------

/// [start, end) of the subtree rooted at `idx`: the line itself plus all
/// following lines with strictly greater indent.
#[uniffi::export]
pub fn subtree_range(doc: HlDoc, idx: u32) -> LineRange {
    let i = idx as usize;
    if i >= doc.lines.len() {
        return LineRange { start: idx, end: idx };
    }
    let root = doc.lines[i].indent;
    let mut end = i + 1;
    while end < doc.lines.len() && doc.lines[end].indent > root {
        end += 1;
    }
    LineRange { start: i as u32, end: end as u32 }
}

/// Number of DIRECT children (indent == parent + 1) under `idx`.
#[uniffi::export]
pub fn child_count(doc: HlDoc, idx: u32) -> u32 {
    let i = idx as usize;
    if i >= doc.lines.len() {
        return 0;
    }
    let root = doc.lines[i].indent;
    let LineRange { start: _, end } = subtree_range(doc.clone(), idx);
    let mut count = 0;
    for j in (i + 1)..(end as usize) {
        if doc.lines[j].indent == root + 1 {
            count += 1;
        }
    }
    count
}

#[uniffi::export]
pub fn has_children(doc: HlDoc, idx: u32) -> bool {
    let LineRange { start, end } = subtree_range(doc, idx);
    end > start + 1
}

// -------------------- edits --------------------

#[uniffi::export]
pub fn set_line_text(doc: HlDoc, idx: u32, text: String) -> HlDoc {
    let mut d = doc;
    if let Some(l) = d.lines.get_mut(idx as usize) {
        l.text = text;
    }
    d
}

/// Insert a new line at `idx` (shifting the rest down). `indent` clamped to
/// [0, prev_line.indent + 1] so no level gaps are created.
#[uniffi::export]
pub fn insert_line(doc: HlDoc, idx: u32, indent: u32, text: String) -> HlDoc {
    let mut d = doc;
    let i = (idx as usize).min(d.lines.len());
    let max_indent = if i == 0 { 0 } else { d.lines[i - 1].indent + 1 };
    let clamped = indent.min(max_indent);
    d.lines.insert(i, HlLine { indent: clamped, text });
    d
}

#[uniffi::export]
pub fn delete_subtree(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let LineRange { start, end } = subtree_range(d.clone(), idx);
    if (start as usize) < d.lines.len() {
        d.lines.drain(start as usize..end as usize);
    }
    d
}

/// Indent the subtree at `idx` by one level, but only if the root can become
/// a child of the preceding line (no level gaps).
#[uniffi::export]
pub fn indent_subtree(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let i = idx as usize;
    if i >= d.lines.len() || i == 0 {
        return d;
    }
    let root = d.lines[i].indent;
    let max_allowed = d.lines[i - 1].indent + 1;
    if root >= max_allowed {
        return d;
    }
    let LineRange { start, end } = subtree_range(d.clone(), idx);
    for j in (start as usize)..(end as usize) {
        d.lines[j].indent += 1;
    }
    d
}

/// Outdent the subtree at `idx` by one level (clamped at 0).
#[uniffi::export]
pub fn outdent_subtree(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let i = idx as usize;
    if i >= d.lines.len() || d.lines[i].indent == 0 {
        return d;
    }
    let LineRange { start, end } = subtree_range(d.clone(), idx);
    for j in (start as usize)..(end as usize) {
        if d.lines[j].indent > 0 {
            d.lines[j].indent -= 1;
        }
    }
    d
}

/// Move the subtree at `idx` above its previous sibling (same indent, same
/// parent). No-op if there is no previous sibling.
#[uniffi::export]
pub fn move_subtree_up(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let i = idx as usize;
    if i >= d.lines.len() {
        return d;
    }
    let root = d.lines[i].indent;
    let LineRange { start, end } = subtree_range(d.clone(), idx);
    let (start, end) = (start as usize, end as usize);
    if start == 0 {
        return d;
    }
    // Find previous sibling root: nearest line above `start` with the same
    // indent, not crossing a shallower (parent) line.
    let mut prev_root: Option<usize> = None;
    let mut j = start;
    while j > 0 {
        j -= 1;
        if d.lines[j].indent < root {
            break; // parent boundary, no previous sibling
        }
        if d.lines[j].indent == root {
            prev_root = Some(j);
            break;
        }
    }
    let Some(ps) = prev_root else { return d };
    let block_b: Vec<HlLine> = d.lines[start..end].to_vec();
    let block_a: Vec<HlLine> = d.lines[ps..start].to_vec();
    let mut new = d.lines[..ps].to_vec();
    new.extend(block_b);
    new.extend(block_a);
    new.extend_from_slice(&d.lines[end..]);
    d.lines = new;
    d
}

/// Move the subtree at `idx` below its next sibling. No-op if none.
#[uniffi::export]
pub fn move_subtree_down(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let i = idx as usize;
    if i >= d.lines.len() {
        return d;
    }
    let root = d.lines[i].indent;
    let LineRange { start, end } = subtree_range(d.clone(), idx);
    let (start, end) = (start as usize, end as usize);
    if end >= d.lines.len() || d.lines[end].indent != root {
        return d; // next line is shallower (parent's sibling) -> no next sibling
    }
    let next = subtree_range(d.clone(), end as u32);
    let n_end = next.end as usize;
    let block_a: Vec<HlLine> = d.lines[start..end].to_vec();
    let block_b: Vec<HlLine> = d.lines[end..n_end].to_vec();
    let mut new = d.lines[..start].to_vec();
    new.extend(block_b);
    new.extend(block_a);
    new.extend_from_slice(&d.lines[n_end..]);
    d.lines = new;
    d
}

/// Toggle a leading checkbox on the line body: ` ` <-> `x`. Other markers
/// (`X` normalised to ` `; `O`/`o`/`-`/`_` left as-is). No-op if no checkbox.
#[uniffi::export]
pub fn toggle_checkbox(doc: HlDoc, idx: u32) -> HlDoc {
    let mut d = doc;
    let Some(line) = d.lines.get_mut(idx as usize) else {
        return d;
    };
    let chars: Vec<char> = line.text.chars().collect();
    if chars.len() >= 3 && chars[0] == '[' && chars[2] == ']' {
        let new_marker = match chars[1] {
            ' ' => Some('x'),
            'x' | 'X' => Some(' '),
            _ => None,
        };
        if let Some(m) = new_marker {
            let mut nc = chars.clone();
            nc[1] = m;
            line.text = nc.into_iter().collect();
        }
    }
    d
}

// -------------------- reference resolution --------------------

/// Resolve a HyperList reference to a line index. Handles slash-paths
/// (`a/b/c`) by walking the hierarchy: each component is matched (case-
/// insensitive substring, tolerating qualifier/operator/comment prefixes
/// like `[1+] `, `S: `, `(...)`) against a descendant of the previous match.
/// Lands on the leaf component. A single component (no `/`) matches by
/// trimmed-equality first, then substring.
#[uniffi::export]
pub fn resolve_reference(doc: HlDoc, target: String) -> Option<u32> {
    let t = target
        .trim()
        .trim_start_matches('<')
        .trim_end_matches('>')
        .trim()
        .to_string();
    if t.is_empty() {
        return None;
    }
    let lines = &doc.lines;
    let parts: Vec<String> = t
        .split('/')
        .map(|p| p.trim().to_lowercase())
        .filter(|p| !p.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }

    if parts.len() == 1 {
        let p = &parts[0];
        // Exact item match (trimmed, case-insensitive) wins.
        for (i, l) in lines.iter().enumerate() {
            if l.text.trim().to_lowercase() == *p {
                return Some(i as u32);
            }
        }
        // Fallback: first substring match.
        for (i, l) in lines.iter().enumerate() {
            if l.text.to_lowercase().contains(p.as_str()) {
                return Some(i as u32);
            }
        }
        return None;
    }

    // Path walk: each component must be a descendant of the previous match.
    let mut search_start = 0usize;
    let mut search_end = lines.len();
    let mut parent_indent: i64 = -1;
    let mut found: Option<usize> = None;
    for part in &parts {
        let mut hit: Option<usize> = None;
        let mut j = search_start;
        while j < search_end {
            let l = &lines[j];
            if (l.indent as i64) > parent_indent && l.text.to_lowercase().contains(part.as_str()) {
                hit = Some(j);
                break;
            }
            j += 1;
        }
        let h = hit?;
        found = Some(h);
        parent_indent = lines[h].indent as i64;
        search_start = h + 1;
        let mut e = h + 1;
        while e < lines.len() && (lines[e].indent as i64) > parent_indent {
            e += 1;
        }
        search_end = e;
    }
    found.map(|i| i as u32)
}

// -------------------- renumbering --------------------

/// If `text` starts with a numeric identifier (`1`, `1.`, `1.2.3`, `1.2.3.`)
/// terminated by a space or end-of-line, return (byte index just past the
/// identifier and its trailing space, had_trailing_period). All identifier
/// chars are ASCII, so byte indexing is safe.
fn numeric_identifier(text: &str) -> Option<(usize, bool)> {
    let b = text.as_bytes();
    let mut i = 0;
    let mut saw_digit = false;
    while i < b.len() && (b[i].is_ascii_digit() || b[i] == b'.') {
        if b[i].is_ascii_digit() {
            saw_digit = true;
        }
        i += 1;
    }
    if !saw_digit || i == 0 {
        return None;
    }
    if i < b.len() && b[i] != b' ' {
        return None; // identifier must be space- or EOL-terminated
    }
    let trailing = b[i - 1] == b'.';
    let end = if i < b.len() && b[i] == b' ' { i + 1 } else { i };
    Some((end, trailing))
}

/// Renumber items that carry numeric identifiers, by hierarchy position.
/// Within each sibling group, only the children that already have a numeric
/// identifier are renumbered (sequentially); unnumbered siblings keep their
/// text and do not consume a number. Numbers are hierarchical (parent number
/// dotted prefix); a top-level group preserves the trailing-period style of
/// its first numbered item (`1.` vs `1`). Leaves non-numbered lists alone.
#[uniffi::export]
pub fn renumber(doc: HlDoc) -> HlDoc {
    let mut d = doc;
    if !d.lines.is_empty() {
        let min_indent = d.lines.iter().map(|l| l.indent).min().unwrap_or(0);
        let n = d.lines.len();
        renumber_group(&mut d.lines, 0, n, min_indent, "");
    }
    d
}

fn renumber_group(lines: &mut [HlLine], start: usize, end: usize, level: u32, prefix: &str) {
    // Trailing-period style for this group: from its first numbered child.
    let mut trailing = false;
    {
        let mut j = start;
        while j < end {
            if lines[j].indent == level {
                if let Some((_, tp)) = numeric_identifier(&lines[j].text) {
                    trailing = tp;
                    break;
                }
            }
            j += 1;
        }
    }

    let mut counter: u32 = 0;
    let mut i = start;
    while i < end {
        if lines[i].indent != level {
            i += 1;
            continue;
        }
        // subtree end for this child
        let mut se = i + 1;
        while se < end && lines[se].indent > level {
            se += 1;
        }
        if let Some((id_end, _)) = numeric_identifier(&lines[i].text) {
            counter += 1;
            let num = if prefix.is_empty() {
                counter.to_string()
            } else {
                format!("{prefix}.{counter}")
            };
            let display = if prefix.is_empty() && trailing {
                format!("{num}.")
            } else {
                num.clone()
            };
            let content = lines[i].text[id_end..].trim_start().to_string();
            lines[i].text = if content.is_empty() {
                display
            } else {
                format!("{display} {content}")
            };
            renumber_group(lines, i + 1, se, level + 1, &num);
        } else {
            // Unnumbered item: its numbered children renumber afresh.
            renumber_group(lines, i + 1, se, level + 1, "");
        }
        i = se;
    }
}

// -------------------- subtree relocation (drag-reorder) --------------------

/// Move the subtree rooted at `from_idx` to sit immediately before
/// `before_idx` (an index into the ORIGINAL doc; use lines.len() to append),
/// re-indenting the whole subtree so its root becomes `new_indent` (clamped
/// to >= 0) and descendants keep their relative depth. No-op if `before_idx`
/// falls inside the moved subtree.
#[uniffi::export]
pub fn move_subtree_before(doc: HlDoc, from_idx: u32, before_idx: u32, new_indent: u32) -> HlDoc {
    let mut d = doc;
    let n = d.lines.len();
    let from = from_idx as usize;
    if from >= n {
        return d;
    }
    let LineRange { start, end } = subtree_range(d.clone(), from_idx);
    let (s, e) = (start as usize, end as usize);
    let before = (before_idx as usize).min(n);
    // Can't drop a subtree into itself.
    if before > s && before < e {
        return d;
    }
    let root_indent = d.lines[s].indent as i64;
    let delta = new_indent as i64 - root_indent;
    let mut block: Vec<HlLine> = d.lines[s..e].to_vec();
    for l in block.iter_mut() {
        l.indent = ((l.indent as i64) + delta).max(0) as u32;
    }
    // Remove the block.
    d.lines.drain(s..e);
    // Map the insertion point past the removal.
    let insert_at = if before <= s { before } else { before - (e - s) };
    let insert_at = insert_at.min(d.lines.len());
    for (k, l) in block.into_iter().enumerate() {
        d.lines.insert(insert_at + k, l);
    }
    d
}

// -------------------- tests --------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn doc(s: &str) -> HlDoc {
        parse_doc(s.to_string())
    }

    #[test]
    fn round_trip_tabs() {
        let src = "Root\n\tChild A\n\t\tGrandchild\n\tChild B\n";
        let d = doc(src);
        assert_eq!(d.lines.len(), 4);
        assert_eq!(d.lines[0], HlLine { indent: 0, text: "Root".into() });
        assert_eq!(d.lines[2], HlLine { indent: 2, text: "Grandchild".into() });
        assert_eq!(serialize_doc(d), src);
    }

    #[test]
    fn asterisk_indent_normalises_to_tabs() {
        let d = doc("Root\n*Child\n**Grand\n");
        assert_eq!(d.lines[1].indent, 1);
        assert_eq!(d.lines[2].indent, 2);
        assert_eq!(serialize_doc(d), "Root\n\tChild\n\t\tGrand\n");
    }

    #[test]
    fn blank_lines_preserved() {
        let d = doc("a\n\nb\n");
        assert_eq!(d.lines.len(), 3);
        assert_eq!(d.lines[1], HlLine { indent: 0, text: "".into() });
    }

    #[test]
    fn subtree_range_basic() {
        let d = doc("Root\n\tA\n\t\tA1\n\tB\nRoot2\n");
        // Root subtree spans lines 0..4 (Root, A, A1, B)
        assert_eq!(subtree_range(d.clone(), 0), LineRange { start: 0, end: 4 });
        // A subtree spans 1..3 (A, A1)
        assert_eq!(subtree_range(d.clone(), 1), LineRange { start: 1, end: 3 });
        // leaf
        assert_eq!(subtree_range(d.clone(), 2), LineRange { start: 2, end: 3 });
    }

    #[test]
    fn child_count_direct_only() {
        let d = doc("Root\n\tA\n\t\tA1\n\tB\n");
        assert_eq!(child_count(d.clone(), 0), 2); // A, B (not A1)
        assert_eq!(child_count(d.clone(), 1), 1); // A1
        assert!(has_children(d.clone(), 0));
        assert!(!has_children(d, 3));
    }

    #[test]
    fn indent_outdent_clamped() {
        let d = doc("A\nB\n");
        // B can become child of A.
        let d = indent_subtree(d, 1);
        assert_eq!(d.lines[1].indent, 1);
        // Indenting again would create a 2-gap under A (indent 0) -> no-op.
        let d = indent_subtree(d, 1);
        assert_eq!(d.lines[1].indent, 1);
        // First line can never indent.
        let d = indent_subtree(d, 0);
        assert_eq!(d.lines[0].indent, 0);
        // Outdent back to 0.
        let d = outdent_subtree(d, 1);
        assert_eq!(d.lines[1].indent, 0);
        // Outdent at 0 -> no-op.
        let d = outdent_subtree(d, 1);
        assert_eq!(d.lines[1].indent, 0);
    }

    #[test]
    fn indent_moves_whole_subtree() {
        let d = doc("A\nB\n\tB1\n");
        // Indent B (with child B1) under A.
        let d = indent_subtree(d, 1);
        assert_eq!(d.lines[1].indent, 1); // B
        assert_eq!(d.lines[2].indent, 2); // B1 followed
    }

    #[test]
    fn move_subtree_up_swaps_siblings() {
        let d = doc("Root\n\tA\n\t\tA1\n\tB\n\t\tB1\n");
        // Move B (idx 3, with child B1) above A.
        let d = move_subtree_up(d, 3);
        let txt = serialize_doc(d);
        assert_eq!(txt, "Root\n\tB\n\t\tB1\n\tA\n\t\tA1\n");
    }

    #[test]
    fn move_subtree_up_no_prev_sibling() {
        let d = doc("Root\n\tA\n\t\tA1\n");
        // A is the first child of Root; no previous sibling.
        let d2 = move_subtree_up(d.clone(), 1);
        assert_eq!(d2, d);
    }

    #[test]
    fn move_subtree_down_swaps_siblings() {
        let d = doc("Root\n\tA\n\t\tA1\n\tB\n");
        // Move A down past B.
        let d = move_subtree_down(d, 1);
        assert_eq!(serialize_doc(d), "Root\n\tB\n\tA\n\t\tA1\n");
    }

    #[test]
    fn toggle_checkbox_cycle() {
        let d = doc("[ ] task\n[x] done\n[O] inprog\nplain\n");
        let d = toggle_checkbox(d, 0);
        assert_eq!(d.lines[0].text, "[x] task");
        let d = toggle_checkbox(d, 1);
        assert_eq!(d.lines[1].text, "[ ] done");
        // [O] left untouched.
        let d = toggle_checkbox(d, 2);
        assert_eq!(d.lines[2].text, "[O] inprog");
        // No checkbox -> unchanged.
        let d = toggle_checkbox(d, 3);
        assert_eq!(d.lines[3].text, "plain");
    }

    #[test]
    fn insert_and_delete_subtree() {
        let d = doc("Root\n\tA\n\t\tA1\n");
        let d = insert_line(d, 1, 1, "New".into());
        assert_eq!(d.lines[1].text, "New");
        assert_eq!(d.lines[1].indent, 1);
        // Delete the A subtree (now at idx 2).
        let d = delete_subtree(d, 2);
        assert_eq!(serialize_doc(d), "Root\n\tNew\n");
    }

    #[test]
    fn unicode_round_trip() {
        let src = "Familie\n\tKjøre Pål til skolen\n\t\t[x] Håndtere æøå\n";
        let d = doc(src);
        assert_eq!(serialize_doc(d), src);
    }

    // ---- reference resolution ----

    #[test]
    fn resolve_path_reference_from_bug_report() {
        // Mirrors HyperList.hl: the reported failing reference
        // <Hyperlist item/Starter/Identifier>. Lines carry qualifier /
        // operator / comment prefixes the path components must tolerate.
        let d = doc(
            "HyperList\n\
             \t[1+] HyperList Item\n\
             \t\t[?] Starter; OR: \n\
             \t\t\tIdentifier (Numbers: Format = \"1.1.1.1\")\n\
             \t\t\tMulti-line Indicator = \"+\"\n\
             \t\t[?] Type\n",
        );
        let idx = resolve_reference(d.clone(), "Hyperlist item/Starter/Identifier".into());
        assert_eq!(idx, Some(3)); // the Identifier line
        // Absolute path including the root also works.
        let idx2 = resolve_reference(d.clone(), "<HyperList/HyperList Item/Type>".into());
        assert_eq!(idx2, Some(5));
    }

    #[test]
    fn resolve_single_component_reference() {
        let d = doc("Root\n\tAlpha\n\tBeta\n");
        assert_eq!(resolve_reference(d.clone(), "Beta".into()), Some(2));
        assert_eq!(resolve_reference(d.clone(), "<Alpha>".into()), Some(1));
        assert_eq!(resolve_reference(d, "Nonexistent".into()), None);
    }

    // ---- renumber ----

    #[test]
    fn renumber_hierarchical() {
        let d = doc(
            "List\n\
             \t3. first\n\
             \t\t9. sub a\n\
             \t\t9. sub b\n\
             \t7. second\n",
        );
        let r = renumber(d);
        // Top-level group used the `N.` style (trailing period), preserved;
        // nested levels use dotted ids without a trailing period.
        assert_eq!(serialize_doc(r), "List\n\t1. first\n\t\t1.1 sub a\n\t\t1.2 sub b\n\t2. second\n");
    }

    #[test]
    fn renumber_preserves_trailing_period_at_top() {
        let d = doc("1. a\n2. b\n5. c\n");
        let r = renumber(d);
        assert_eq!(serialize_doc(r), "1. a\n2. b\n3. c\n");
    }

    #[test]
    fn renumber_leaves_unnumbered_alone_and_skips_them() {
        let d = doc(
            "Root\n\
             \t5. step one\n\
             \ta header\n\
             \t5. step two\n",
        );
        let r = renumber(d);
        // header keeps its text; numbered steps become 1, 2 (header doesn't
        // consume a number). The `N.` trailing-period style is preserved.
        assert_eq!(serialize_doc(r), "Root\n\t1. step one\n\ta header\n\t2. step two\n");
    }

    #[test]
    fn renumber_noop_on_unnumbered_list() {
        let src = "Root\n\tAlpha\n\tBeta\n";
        let d = doc(src);
        assert_eq!(serialize_doc(renumber(d)), src);
    }

    // ---- subtree relocation ----

    #[test]
    fn move_subtree_before_forward_with_reindent() {
        let d = doc("Root\n\tA\n\t\tA1\n\tB\n");
        // Move A (idx 1, subtree A+A1) to before B's position... actually move
        // it to the very end as a child of Root (indent 1). before_idx = 4 (len).
        let r = move_subtree_before(d, 1, 4, 1);
        assert_eq!(serialize_doc(r), "Root\n\tB\n\tA\n\t\tA1\n");
    }

    #[test]
    fn move_subtree_changes_depth() {
        let d = doc("Root\n\tA\n\tB\n\t\tB1\n");
        // Move B (idx 2, subtree B+B1) to before A (idx 1) at indent 1.
        // B root indent 1 -> new 1 (delta 0), B1 stays 2.
        let r = move_subtree_before(d, 2, 1, 1);
        assert_eq!(serialize_doc(r), "Root\n\tB\n\t\tB1\n\tA\n");
    }

    #[test]
    fn move_subtree_promote_to_root() {
        let d = doc("Root\n\tA\n\t\tA1\n\t\t\tA1x\n");
        // Promote A1 (idx 2, with child A1x) to top level (indent 0), before Root.
        let r = move_subtree_before(d, 2, 0, 0);
        assert_eq!(serialize_doc(r), "A1\n\tA1x\nRoot\n\tA\n");
    }

    #[test]
    fn move_subtree_into_itself_is_noop() {
        let d = doc("Root\n\tA\n\t\tA1\n");
        // before_idx inside A's subtree (2) -> no-op.
        let r = move_subtree_before(d.clone(), 1, 2, 1);
        assert_eq!(r, d);
    }
}
