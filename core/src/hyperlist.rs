// Hyperlist: tab-indented hierarchy. One leading tab = category, two
// leading tabs = item under the previous category. Matches what kastrup's
// z-triage and scribe write to ~/.tasks/todo.hl.
//
// Ported 1:1 from the v0.3.0 Kotlin implementation in
// /home/geir/Main/G/GIT-isene/tasks/app/src/main/kotlin/com/isene/tasks/data/Hyperlist.kt
// so the new tasks app's saves stay byte-compatible with the old one.
//
// API shape note: every transform takes ownership of the input Hyperlist
// and returns a new one. This matches the existing Kotlin immutable
// data-class pattern (Compose sees new identities, recomposes immediately)
// and stays simple over the UniFFI boundary.

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Item {
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Category {
    pub name: String,
    pub items: Vec<Item>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, uniffi::Record)]
pub struct Hyperlist {
    pub categories: Vec<Category>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WidgetRow {
    pub category: String,
    pub item: String,
}

// -------------------- parse / serialize --------------------

#[uniffi::export]
pub fn parse(content: String) -> Hyperlist {
    let mut categories: Vec<Category> = Vec::new();
    let mut current_name: Option<String> = None;
    let mut current_items: Vec<Item> = Vec::new();

    let commit = |name_opt: &mut Option<String>,
                  items: &mut Vec<Item>,
                  out: &mut Vec<Category>| {
        if let Some(n) = name_opt.take() {
            out.push(Category {
                name: n,
                items: std::mem::take(items),
            });
        } else {
            items.clear();
        }
    };

    for line in content.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let leading_tabs = line.chars().take_while(|&c| c == '\t').count();
        match leading_tabs {
            1 => {
                commit(&mut current_name, &mut current_items, &mut categories);
                let name = line[1..].trim().to_string();
                if !name.is_empty() {
                    current_name = Some(name);
                    current_items = Vec::new();
                }
            }
            n if n >= 2 => {
                // Strip exactly two leading tabs for the item text. Anything
                // deeper flattens to a single item; matches the existing
                // app's 2-level subset.
                let text = line.trim_start_matches('\t').to_string();
                if current_name.is_some() {
                    current_items.push(Item { text });
                }
            }
            _ => {
                // Zero-indent lines (top-level prose, comments) are skipped.
            }
        }
    }
    commit(&mut current_name, &mut current_items, &mut categories);

    Hyperlist { categories }
}

#[uniffi::export]
pub fn serialize(hl: Hyperlist) -> String {
    let mut out = String::new();
    for cat in &hl.categories {
        out.push('\t');
        out.push_str(&cat.name);
        out.push('\n');
        for item in &cat.items {
            out.push_str("\t\t");
            out.push_str(&item.text);
            out.push('\n');
        }
    }
    out
}

// -------------------- category-level transforms --------------------

#[uniffi::export]
pub fn add_category(hl: Hyperlist, name: String) -> Hyperlist {
    let mut h = hl;
    h.categories.push(Category {
        name,
        items: Vec::new(),
    });
    h
}

#[uniffi::export]
pub fn rename_category(hl: Hyperlist, cat_idx: u32, new_name: String) -> Hyperlist {
    let mut h = hl;
    if let Some(c) = h.categories.get_mut(cat_idx as usize) {
        c.name = new_name;
    }
    h
}

#[uniffi::export]
pub fn delete_category(hl: Hyperlist, cat_idx: u32) -> Hyperlist {
    let mut h = hl;
    let idx = cat_idx as usize;
    if idx < h.categories.len() {
        h.categories.remove(idx);
    }
    h
}

#[uniffi::export]
pub fn move_category_to(hl: Hyperlist, from_idx: u32, to_idx: u32) -> Hyperlist {
    let mut h = hl;
    let from = from_idx as usize;
    if from >= h.categories.len() {
        return h;
    }
    let cat = h.categories.remove(from);
    let to = (to_idx as usize).min(h.categories.len());
    h.categories.insert(to, cat);
    h
}

// -------------------- item-level transforms --------------------

#[uniffi::export]
pub fn add_item(hl: Hyperlist, cat_idx: u32, text: String) -> Hyperlist {
    let mut h = hl;
    if let Some(c) = h.categories.get_mut(cat_idx as usize) {
        c.items.push(Item { text });
    }
    h
}

#[uniffi::export]
pub fn rename_item(
    hl: Hyperlist,
    cat_idx: u32,
    item_idx: u32,
    new_text: String,
) -> Hyperlist {
    let mut h = hl;
    if let Some(c) = h.categories.get_mut(cat_idx as usize) {
        if let Some(it) = c.items.get_mut(item_idx as usize) {
            it.text = new_text;
        }
    }
    h
}

#[uniffi::export]
pub fn delete_item(hl: Hyperlist, cat_idx: u32, item_idx: u32) -> Hyperlist {
    let mut h = hl;
    if let Some(c) = h.categories.get_mut(cat_idx as usize) {
        let i = item_idx as usize;
        if i < c.items.len() {
            c.items.remove(i);
        }
    }
    h
}

/// Move an item to a specific (categoryIndex, position) destination.
/// `to_item_idx` is the position WITHIN the destination category AFTER
/// the source has been removed; matches the existing Kotlin semantics.
#[uniffi::export]
pub fn move_item_to(
    hl: Hyperlist,
    from_cat_idx: u32,
    from_item_idx: u32,
    to_cat_idx: u32,
    to_item_idx: u32,
) -> Hyperlist {
    let mut h = hl;
    let fc = from_cat_idx as usize;
    let fi = from_item_idx as usize;
    let tc = to_cat_idx as usize;
    let src_ok = h
        .categories
        .get(fc)
        .map(|c| fi < c.items.len())
        .unwrap_or(false);
    if !src_ok {
        return h;
    }
    let item = h.categories[fc].items.remove(fi);
    if tc >= h.categories.len() {
        // Destination gone; reinsert into source at the same position.
        h.categories[fc].items.insert(fi, item);
        return h;
    }
    let target_len = h.categories[tc].items.len();
    let ti = (to_item_idx as usize).min(target_len);
    h.categories[tc].items.insert(ti, item);
    h
}

// -------------------- display ordering --------------------

/// Move the "Inbox" category (case-insensitive) to the front, keeping every
/// other category in its existing relative order. No-op when there's no
/// Inbox or it's already first. Applied at load time (app) and inside
/// `widget_rows` (widget) so the inbox and its items surface at the top
/// everywhere. The capture target (vox / relay / kastrup drop new tasks into
/// Inbox) is then always the first thing visible.
#[uniffi::export]
pub fn inbox_first(hl: Hyperlist) -> Hyperlist {
    let mut h = hl;
    if let Some(pos) = h
        .categories
        .iter()
        .position(|c| c.name.trim().eq_ignore_ascii_case("inbox"))
    {
        if pos > 0 {
            let cat = h.categories.remove(pos);
            h.categories.insert(0, cat);
        }
    }
    h
}

// -------------------- widget read API --------------------

/// Flat list for the Glance widget. Returns up to `max_items` rows with the
/// Inbox first (see `inbox_first`), then remaining categories in order.
/// Empty categories are skipped.
#[uniffi::export]
pub fn widget_rows(hl: Hyperlist, max_items: u32) -> Vec<WidgetRow> {
    let hl = inbox_first(hl);
    let mut out = Vec::new();
    let cap = max_items as usize;
    for cat in &hl.categories {
        for item in &cat.items {
            if out.len() >= cap {
                return out;
            }
            out.push(WidgetRow {
                category: cat.name.clone(),
                item: item.text.clone(),
            });
        }
    }
    out
}

// -------------------- tests --------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> &'static str {
        "\tPersonal\n\t\tRenew passport\n\t\tCall the dentist\n\tWork\n\t\tReview Q3 plan\n"
    }

    #[test]
    fn parse_round_trip() {
        let hl = parse(sample().to_string());
        assert_eq!(hl.categories.len(), 2);
        assert_eq!(hl.categories[0].name, "Personal");
        assert_eq!(hl.categories[0].items.len(), 2);
        assert_eq!(hl.categories[0].items[0].text, "Renew passport");
        assert_eq!(hl.categories[1].name, "Work");
        assert_eq!(hl.categories[1].items[0].text, "Review Q3 plan");

        let back = serialize(hl);
        assert_eq!(back, sample());
    }

    #[test]
    fn parse_skips_blank_and_zero_indent() {
        let input = "header line\n\n\tCat\n\t\titem\n\n";
        let hl = parse(input.to_string());
        assert_eq!(hl.categories.len(), 1);
        assert_eq!(hl.categories[0].name, "Cat");
        assert_eq!(hl.categories[0].items.len(), 1);
    }

    #[test]
    fn parse_deeper_indents_flatten_to_items() {
        // Existing v0.3.0 behaviour: anything ≥ 2 tabs becomes an item
        // under the current category. Deeper structure flattens; we
        // document this in CLAUDE.md.
        let input = "\tCat\n\t\titem\n\t\t\tdeep child\n";
        let hl = parse(input.to_string());
        assert_eq!(hl.categories[0].items.len(), 2);
        assert_eq!(hl.categories[0].items[1].text, "deep child");
    }

    #[test]
    fn add_and_rename_category() {
        let h = Hyperlist::default();
        let h = add_category(h, "A".into());
        let h = add_category(h, "B".into());
        let h = rename_category(h, 0, "Alpha".into());
        assert_eq!(h.categories[0].name, "Alpha");
        assert_eq!(h.categories[1].name, "B");
    }

    #[test]
    fn delete_category_bounds() {
        let mut h = Hyperlist::default();
        h.categories.push(Category {
            name: "A".into(),
            items: vec![],
        });
        let h = delete_category(h, 5); // out of range — no-op
        assert_eq!(h.categories.len(), 1);
        let h = delete_category(h, 0);
        assert!(h.categories.is_empty());
    }

    #[test]
    fn move_category_to_within_range() {
        let h = Hyperlist::default();
        let h = add_category(h, "A".into());
        let h = add_category(h, "B".into());
        let h = add_category(h, "C".into());
        let h = move_category_to(h, 0, 2);
        assert_eq!(
            h.categories.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(),
            vec!["B", "C", "A"]
        );
    }

    #[test]
    fn add_item_and_rename() {
        let h = add_category(Hyperlist::default(), "Cat".into());
        let h = add_item(h, 0, "one".into());
        let h = add_item(h, 0, "two".into());
        let h = rename_item(h, 0, 1, "TWO".into());
        assert_eq!(h.categories[0].items[1].text, "TWO");
    }

    #[test]
    fn delete_item_bounds() {
        let h = add_category(Hyperlist::default(), "Cat".into());
        let h = add_item(h, 0, "x".into());
        let h = delete_item(h, 0, 9); // out of range — no-op
        assert_eq!(h.categories[0].items.len(), 1);
        let h = delete_item(h, 0, 0);
        assert!(h.categories[0].items.is_empty());
    }

    #[test]
    fn move_item_across_categories() {
        let h = add_category(Hyperlist::default(), "A".into());
        let h = add_category(h, "B".into());
        let h = add_item(h, 0, "x".into());
        let h = add_item(h, 0, "y".into());
        let h = move_item_to(h, 0, 1, 1, 0);
        assert_eq!(h.categories[0].items.len(), 1);
        assert_eq!(h.categories[0].items[0].text, "x");
        assert_eq!(h.categories[1].items.len(), 1);
        assert_eq!(h.categories[1].items[0].text, "y");
    }

    #[test]
    fn inbox_moves_to_front_preserving_order() {
        let h = add_category(Hyperlist::default(), "Personal".into());
        let h = add_category(h, "Work".into());
        let h = add_category(h, "Inbox".into());
        let h = inbox_first(h);
        assert_eq!(
            h.categories.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(),
            vec!["Inbox", "Personal", "Work"],
        );
    }

    #[test]
    fn inbox_first_is_noop_without_inbox_or_when_first() {
        let h = add_category(Hyperlist::default(), "A".into());
        let h = add_category(h, "B".into());
        let before = h.clone();
        assert_eq!(inbox_first(h), before); // no Inbox → unchanged

        let h = add_category(Hyperlist::default(), "inbox".into()); // case-insensitive
        let h = add_category(h, "Other".into());
        let out = inbox_first(h);
        assert_eq!(out.categories[0].name, "inbox"); // already first → still first
        assert_eq!(out.categories[1].name, "Other");
    }

    #[test]
    fn widget_rows_puts_inbox_items_first() {
        let h = add_category(Hyperlist::default(), "Work".into());
        let h = add_item(h, 0, "ship it".into());
        let h = add_category(h, "Inbox".into());
        let h = add_item(h, 1, "captured note".into());
        let rows = widget_rows(h, 12);
        assert_eq!(rows[0].category, "Inbox");
        assert_eq!(rows[0].item, "captured note");
        assert_eq!(rows[1].category, "Work");
    }

    #[test]
    fn widget_rows_respects_cap() {
        let h = add_category(Hyperlist::default(), "A".into());
        let h = add_item(h, 0, "1".into());
        let h = add_item(h, 0, "2".into());
        let h = add_category(h, "B".into());
        let h = add_item(h, 1, "3".into());
        let rows = widget_rows(h, 2);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].item, "1");
        assert_eq!(rows[1].item, "2");
    }

    #[test]
    fn unicode_in_categories_and_items() {
        // Nordic chars in both category and item; must not slice mid-utf8.
        let input = "\tFamilie\n\t\tKjøre Pål til skolen\n\t\tHåndtere æ-ø-å\n";
        let hl = parse(input.to_string());
        assert_eq!(hl.categories[0].name, "Familie");
        assert_eq!(hl.categories[0].items[0].text, "Kjøre Pål til skolen");
        let back = serialize(hl);
        assert_eq!(back, input);
    }
}
