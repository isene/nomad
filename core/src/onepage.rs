// OnePage launcher — widget layout model.
//
// The launcher's only persistent state: where each hosted AppWidget sits on
// the single home page. Free placement, no grid, overlap allowed, so the
// model is just absolute (x, y, w, h) in px per widget. Kotlin owns the file
// I/O (filesDir/layout.json, atomic write); this module owns the format, so
// desktop tools can read/edit a layout file later if useful.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
pub struct WidgetPos {
    pub app_widget_id: i32,
    /// Provider ComponentName as a flat string ("pkg/cls"). Informational —
    /// rebinding after restore goes through the appWidgetId, but the provider
    /// string lets a future import flow re-bind on a new device.
    pub provider: String,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
pub struct Layout {
    pub version: u32,
    pub widgets: Vec<WidgetPos>,
}

#[uniffi::export]
pub fn onepage_empty() -> Layout {
    Layout { version: 1, widgets: vec![] }
}

/// Parse a layout file. None on malformed JSON — the caller falls back to an
/// empty layout rather than crashing the launcher at boot.
#[uniffi::export]
pub fn onepage_parse(json: String) -> Option<Layout> {
    serde_json::from_str(&json).ok()
}

#[uniffi::export]
pub fn onepage_serialize(layout: Layout) -> String {
    serde_json::to_string_pretty(&layout).unwrap_or_else(|_| "{}".into())
}

// ---- The app panel ----
//
// A card of icons that pops up over the home page or over another app. It
// is a grid as tall as it is filled; Kotlin says how many slots wide it is
// (three at the side of the screen, more along the bottom). A slot is empty,
// starts one thing, or is a folder of things. An empty row between two
// groups is a row of empty slots. Kotlin owns the file (filesDir/panel.json)
// and the drawing; the format and the edits are here, so they are tested
// on the laptop.

/// More slots than this are of no use on a phone, and a broken file must
/// not make the launcher ask for a huge list.
const MAX_SLOTS: usize = 300;

/// One thing a tap starts.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Target {
    /// An app, by the activity its icon starts ("pkg/cls").
    App { component: String },
    /// A shortcut an app has handed to the home screen: a contact, a chat.
    Shortcut { pkg: String, id: String },
    /// A phone number a tap calls, and the name it goes by.
    Call { name: String, number: String },
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Enum)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Slot {
    Empty,
    One { target: Target },
    Folder {
        name: String,
        /// The sign drawn for the folder, by its name in the app's table
        /// of signs. Empty: the first letter of the folder's name.
        #[serde(default)]
        icon: String,
        items: Vec<Target>,
    },
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
pub struct Panel {
    pub version: u32,
    /// Row by row. Never ends in an empty slot.
    pub slots: Vec<Slot>,
}

/// Cut the empty slots off the end, so the panel is as tall as its last icon.
fn trimmed(mut panel: Panel) -> Panel {
    while panel.slots.last() == Some(&Slot::Empty) {
        panel.slots.pop();
    }
    panel
}

/// Make room for slot `at`. False when it is too far out.
fn reach(panel: &mut Panel, at: usize) -> bool {
    if at >= MAX_SLOTS {
        return false;
    }
    if panel.slots.len() <= at {
        panel.slots.resize(at + 1, Slot::Empty);
    }
    true
}

#[uniffi::export]
pub fn panel_empty() -> Panel {
    Panel { version: 1, slots: vec![] }
}

/// Parse a panel file. None on malformed JSON, and the caller starts empty.
#[uniffi::export]
pub fn panel_parse(json: String) -> Option<Panel> {
    let mut panel: Panel = serde_json::from_str(&json).ok()?;
    panel.slots.truncate(MAX_SLOTS);
    Some(trimmed(panel))
}

#[uniffi::export]
pub fn panel_serialize(panel: Panel) -> String {
    serde_json::to_string_pretty(&trimmed(panel)).unwrap_or_else(|_| "{}".into())
}

/// The rows in use, for a grid `columns` wide.
#[uniffi::export]
pub fn panel_rows(panel: Panel, columns: u32) -> u32 {
    let columns = columns.max(1) as usize;
    trimmed(panel).slots.len().div_ceil(columns) as u32
}

/// Put `slot` at `at`. Putting `Slot::Empty` there removes what was.
#[uniffi::export]
pub fn panel_put(mut panel: Panel, at: u32, slot: Slot) -> Panel {
    if reach(&mut panel, at as usize) {
        panel.slots[at as usize] = slot;
    }
    trimmed(panel)
}

/// Let two slots change places. One of them may be empty: that is a move.
#[uniffi::export]
pub fn panel_swap(mut panel: Panel, a: u32, b: u32) -> Panel {
    let (a, b) = (a as usize, b as usize);
    if reach(&mut panel, a.max(b)) {
        panel.slots.swap(a, b);
    }
    trimmed(panel)
}

/// Put each of `targets` into the next free slot, from `at` on. This is
/// how a list of ticked apps lands in the panel.
#[uniffi::export]
pub fn panel_fill(mut panel: Panel, at: u32, targets: Vec<Target>) -> Panel {
    let mut at = at as usize;
    for target in targets {
        while panel.slots.get(at).is_some_and(|slot| *slot != Slot::Empty) {
            at += 1;
        }
        if !reach(&mut panel, at) {
            break;
        }
        panel.slots[at] = Slot::One { target };
        at += 1;
    }
    trimmed(panel)
}

/// Drop the icon at `from` into the folder at `to`. When `from` has no
/// single icon, or `to` is no folder, nothing changes.
#[uniffi::export]
pub fn panel_into_folder(mut panel: Panel, from: u32, to: u32) -> Panel {
    let (from, to) = (from as usize, to as usize);
    let Some(Slot::One { target }) = panel.slots.get(from).cloned() else {
        return panel;
    };
    let Some(Slot::Folder { items, .. }) = panel.slots.get_mut(to) else {
        return panel;
    };
    if !items.contains(&target) {
        items.push(target);
    }
    panel.slots[from] = Slot::Empty;
    trimmed(panel)
}

/// True when `target` is in the panel, in a folder too.
#[uniffi::export]
pub fn panel_has(panel: Panel, target: Target) -> bool {
    panel.slots.iter().any(|slot| match slot {
        Slot::Empty => false,
        Slot::One { target: t } => *t == target,
        Slot::Folder { items, .. } => items.contains(&target),
    })
}

/// Add something that came from outside, such as a shortcut an app handed
/// over. It goes after the last icon, so no gap between two groups is
/// filled. Nothing is added twice.
#[uniffi::export]
pub fn panel_add(panel: Panel, target: Target) -> Panel {
    let mut panel = trimmed(panel);
    if panel.slots.len() < MAX_SLOTS && !panel_has(panel.clone(), target.clone()) {
        panel.slots.push(Slot::One { target });
    }
    panel
}

/// Add to the folder at `at`. Nothing is added twice to one folder.
#[uniffi::export]
pub fn panel_folder_add(mut panel: Panel, at: u32, target: Target) -> Panel {
    if let Some(Slot::Folder { items, .. }) = panel.slots.get_mut(at as usize) {
        if !items.contains(&target) {
            items.push(target);
        }
    }
    panel
}

/// Take item number `item` out of the folder at `at`.
#[uniffi::export]
pub fn panel_folder_drop(mut panel: Panel, at: u32, item: u32) -> Panel {
    if let Some(Slot::Folder { items, .. }) = panel.slots.get_mut(at as usize) {
        if (item as usize) < items.len() {
            items.remove(item as usize);
        }
    }
    panel
}

/// The shortcuts of one app that are in the panel. Android keeps a shortcut
/// only while the home app says it is in use, so this list is handed back
/// to Android whenever one is removed.
#[uniffi::export]
pub fn panel_pins(panel: Panel, pkg: String) -> Vec<String> {
    let mut ids = Vec::new();
    let mut take = |target: &Target| {
        if let Target::Shortcut { pkg: p, id } = target {
            if *p == pkg && !ids.contains(id) {
                ids.push(id.clone());
            }
        }
    };
    for slot in &panel.slots {
        match slot {
            Slot::Empty => {}
            Slot::One { target } => take(target),
            Slot::Folder { items, .. } => items.iter().for_each(&mut take),
        }
    }
    ids
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Layout {
        Layout {
            version: 1,
            widgets: vec![
                WidgetPos {
                    app_widget_id: 17,
                    provider: "com.android.calendar/.CalWidget".into(),
                    x: 40,
                    y: 200,
                    w: 800,
                    h: 600,
                },
                WidgetPos {
                    app_widget_id: 18,
                    provider: "com.example.clock/.Big".into(),
                    x: 0,
                    y: 0,
                    w: 400,
                    h: 200,
                },
            ],
        }
    }

    #[test]
    fn roundtrip() {
        let l = sample();
        let json = onepage_serialize(l.clone());
        let back = onepage_parse(json).unwrap();
        assert_eq!(back, l);
    }

    #[test]
    fn parse_spec_example() {
        let json = r#"{
            "version": 1,
            "widgets": [
                { "app_widget_id": 17, "provider": "com.android.calendar/.CalWidget",
                  "x": 40, "y": 200, "w": 800, "h": 600 }
            ]
        }"#;
        let l = onepage_parse(json.into()).unwrap();
        assert_eq!(l.widgets.len(), 1);
        assert_eq!(l.widgets[0].app_widget_id, 17);
        assert_eq!(l.widgets[0].h, 600);
    }

    #[test]
    fn malformed_returns_none() {
        assert!(onepage_parse("not json".into()).is_none());
        assert!(onepage_parse("".into()).is_none());
    }

    #[test]
    fn empty_layout() {
        let e = onepage_empty();
        assert_eq!(e.version, 1);
        assert!(e.widgets.is_empty());
        let back = onepage_parse(onepage_serialize(e.clone())).unwrap();
        assert_eq!(back, e);
    }
}

#[cfg(test)]
mod panel_tests {
    use super::*;

    fn app(name: &str) -> Target {
        Target::App { component: format!("com.example.{name}/.Main") }
    }

    fn chat(id: &str) -> Target {
        Target::Shortcut { pkg: "com.example.chat".into(), id: id.into() }
    }

    fn one(name: &str) -> Slot {
        Slot::One { target: app(name) }
    }

    fn folder(name: &str, items: Vec<Target>) -> Slot {
        Slot::Folder { name: name.into(), icon: String::new(), items }
    }

    #[test]
    fn a_panel_survives_the_file() {
        let mut p = panel_empty();
        p = panel_put(p, 0, one("mail"));
        p = panel_put(p, 4, Slot::One { target: chat("anna") });
        p = panel_put(p, 5, Slot::Folder { name: "Travel".into(), icon: "plane".into(), items: vec![app("maps"), chat("bo")] });
        p = panel_put(p, 6, Slot::One { target: Target::Call { name: "Anna".into(), number: "+1 555 0100".into() } });
        let back = panel_parse(panel_serialize(p.clone())).unwrap();
        assert_eq!(back, p);
        assert_eq!(back.slots.len(), 7);
        assert_eq!(back.slots[1], Slot::Empty);
    }

    #[test]
    fn the_file_reads_as_written_by_hand() {
        let json = r#"{ "version": 1, "slots": [
            { "kind": "one", "target": { "kind": "app", "component": "a/.B" } },
            { "kind": "empty" },
            { "kind": "folder", "name": "Tools", "items": [
                { "kind": "shortcut", "pkg": "c", "id": "7" } ] },
            { "kind": "empty" }
        ] }"#;
        let p = panel_parse(json.into()).unwrap();
        // The empty slot at the end is cut.
        assert_eq!(p.slots.len(), 3);
        assert_eq!(p.slots[0], Slot::One { target: Target::App { component: "a/.B".into() } });
        // A folder from before folders had signs reads with none.
        assert_eq!(p.slots[2], folder("Tools", vec![Target::Shortcut { pkg: "c".into(), id: "7".into() }]));
        assert!(panel_parse("not json".into()).is_none());
        assert!(panel_parse("".into()).is_none());
    }

    #[test]
    fn a_gap_row_stays_and_the_end_is_cut() {
        // One icon in row 0, the next group starts in row 2.
        let mut p = panel_put(panel_empty(), 0, one("mail"));
        p = panel_put(p, 6, one("maps"));
        assert_eq!(panel_rows(p.clone(), 3), 3);
        assert_eq!(p.slots[3], Slot::Empty);
        // Removing the last icon takes the gap row with it.
        p = panel_put(p, 6, Slot::Empty);
        assert_eq!(p.slots.len(), 1);
        assert_eq!(panel_rows(p, 3), 1);
        assert_eq!(panel_rows(panel_empty(), 3), 0);
        // Seven slots are three rows of three.
        assert_eq!(panel_rows(panel_put(panel_empty(), 6, one("a")), 3), 3);
        assert_eq!(panel_rows(panel_put(panel_empty(), 5, one("a")), 3), 2);
    }

    #[test]
    fn a_move_is_a_swap_with_an_empty_slot() {
        let mut p = panel_put(panel_empty(), 0, one("mail"));
        p = panel_put(p, 1, one("maps"));
        // To a slot past the end.
        p = panel_swap(p, 0, 7);
        assert_eq!(p.slots[0], Slot::Empty);
        assert_eq!(p.slots[7], one("mail"));
        // Two icons change places.
        p = panel_swap(p, 7, 1);
        assert_eq!(p.slots[1], one("mail"));
        assert_eq!(p.slots[7], one("maps"));
        // Back to the start: the panel is short again.
        p = panel_swap(p, 7, 0);
        assert_eq!(p.slots, vec![one("maps"), one("mail")]);
    }

    #[test]
    fn a_slot_too_far_out_changes_nothing() {
        let p = panel_put(panel_empty(), 0, one("mail"));
        assert_eq!(panel_put(p.clone(), 1_000_000, one("maps")), p);
        assert_eq!(panel_swap(p.clone(), 0, 1_000_000), p);
    }

    #[test]
    fn what_an_app_hands_over_goes_last_and_only_once() {
        let mut p = panel_put(panel_empty(), 0, one("mail"));
        p = panel_put(p, 6, one("maps"));
        p = panel_add(p, chat("anna"));
        // After the last icon, and the empty row above it is left alone.
        assert_eq!(p.slots[7], Slot::One { target: chat("anna") });
        assert_eq!(p.slots[3], Slot::Empty);
        let again = panel_add(p.clone(), chat("anna"));
        assert_eq!(again, p);
        // One that sits in a folder is not added beside it.
        let mut q = panel_put(panel_empty(), 0, folder("F", vec![]));
        q = panel_folder_add(q, 0, chat("bo"));
        assert!(panel_has(q.clone(), chat("bo")));
        assert_eq!(panel_add(q.clone(), chat("bo")), q);
    }

    #[test]
    fn a_folder_takes_and_gives_up_its_items() {
        let mut p = panel_put(panel_empty(), 2, folder("Tools", vec![]));
        p = panel_folder_add(p, 2, app("maps"));
        p = panel_folder_add(p, 2, app("mail"));
        p = panel_folder_add(p, 2, app("maps"));
        assert_eq!(p.slots[2], folder("Tools", vec![app("maps"), app("mail")]));
        p = panel_folder_drop(p, 2, 0);
        assert_eq!(p.slots[2], folder("Tools", vec![app("mail")]));
        // A wrong number, or a slot that is no folder, changes nothing.
        assert_eq!(panel_folder_drop(p.clone(), 2, 9), p);
        assert_eq!(panel_folder_add(p.clone(), 0, app("x")), p);
        assert_eq!(panel_folder_drop(p.clone(), 40, 0), p);
    }

    #[test]
    fn the_shortcuts_of_one_app_are_listed_for_android() {
        let mut p = panel_put(panel_empty(), 0, Slot::One { target: chat("anna") });
        p = panel_put(p, 1, Slot::One { target: Target::Shortcut { pkg: "other".into(), id: "x".into() } });
        p = panel_put(p, 2, folder("F", vec![chat("bo"), app("mail"), chat("anna")]));
        assert_eq!(panel_pins(p.clone(), "com.example.chat".into()), vec!["anna".to_string(), "bo".to_string()]);
        // With anna out of the slot, she is still in the folder.
        p = panel_put(p, 0, Slot::Empty);
        assert_eq!(panel_pins(p.clone(), "com.example.chat".into()), vec!["bo".to_string(), "anna".to_string()]);
        assert!(panel_pins(p, "nobody".into()).is_empty());
    }

    #[test]
    fn ticked_apps_fill_the_free_slots_in_order() {
        // Slot 1 is taken, so the three go to 0, 2 and 3.
        let p = panel_put(panel_empty(), 1, one("mail"));
        let p = panel_fill(p, 0, vec![app("a"), app("b"), app("c")]);
        assert_eq!(p.slots, vec![one("a"), one("mail"), one("b"), one("c")]);
        // From a slot past the end, with the gap kept.
        let q = panel_fill(panel_put(panel_empty(), 0, one("mail")), 6, vec![app("a")]);
        assert_eq!(q.slots.len(), 7);
        assert_eq!(q.slots[3], Slot::Empty);
        assert_eq!(q.slots[6], one("a"));
        // Nothing ticked changes nothing.
        assert_eq!(panel_fill(q.clone(), 0, vec![]), q);
    }

    #[test]
    fn an_icon_dropped_on_a_folder_goes_into_it() {
        let mut p = panel_put(panel_empty(), 0, one("mail"));
        p = panel_put(p, 1, folder("Tools", vec![app("maps")]));
        p = panel_put(p, 2, one("maps"));
        let q = panel_into_folder(p.clone(), 0, 1);
        assert_eq!(q.slots[0], Slot::Empty);
        assert_eq!(q.slots[1], folder("Tools", vec![app("maps"), app("mail")]));
        // One that is in the folder already leaves its slot and is not doubled.
        let r = panel_into_folder(q, 2, 1);
        assert_eq!(r.slots, vec![Slot::Empty, folder("Tools", vec![app("maps"), app("mail")])]);
        // A folder is not dropped into a folder, and an icon not into an icon.
        assert_eq!(panel_into_folder(p.clone(), 1, 1), p);
        assert_eq!(panel_into_folder(p.clone(), 0, 2), p);
        assert_eq!(panel_into_folder(p.clone(), 9, 1), p);
    }

    #[test]
    fn a_call_is_no_shortcut_for_android() {
        let call = Target::Call { name: "Anna".into(), number: "+1 555 0100".into() };
        let p = panel_put(panel_empty(), 0, Slot::One { target: call.clone() });
        assert!(panel_has(p.clone(), call));
        assert!(panel_pins(p, "com.example.chat".into()).is_empty());
    }
}
