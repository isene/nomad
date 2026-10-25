// The wish lists, shared with the desktop watchit TUI.
//
// The wire format is desktop watchit's `~/.watchit/sync/wish-*.json`:
// `{"series": {"95403": {"on": true, "ts": ...}}, "movies": {...}}`,
// keyed by catalog id within each list. One file per device, like the
// ratings, and newest timestamp per title wins. Taking a title off the
// list is a mark of its own (`on: false`), or the other device's older
// "on" would put it back.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
struct Stored {
    on: bool,
    ts: i64,
}

/// One title put on, or taken off, a wish list, flattened for the FFI.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WishMark {
    /// "movies" or "series".
    pub kind: String,
    pub id: String,
    pub on: bool,
    /// Unix seconds.
    pub ts: i64,
}

/// This device's list after a sync, the marks behind it, and whether
/// the marks changed so this device's file needs writing.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WishSync {
    pub marks: Vec<WishMark>,
    pub list: Vec<String>,
    pub changed: bool,
}

/// Parse one device's wish file. Unreadable JSON yields nothing.
#[uniffi::export]
pub fn parse_wish(json: String) -> Vec<WishMark> {
    let map: HashMap<String, HashMap<String, Stored>> =
        serde_json::from_str(&json).unwrap_or_default();
    map.into_iter()
        .flat_map(|(kind, marks)| marks.into_iter().map(move |(id, s)| WishMark {
            kind: kind.clone(), id, on: s.on, ts: s.ts,
        }))
        .collect()
}

#[uniffi::export]
pub fn serialize_wish(marks: Vec<WishMark>) -> String {
    let mut map: HashMap<String, HashMap<String, Stored>> = HashMap::new();
    for m in marks {
        map.entry(m.kind).or_default().insert(m.id, Stored { on: m.on, ts: m.ts });
    }
    serde_json::to_string_pretty(&map).unwrap_or_else(|_| "{}".to_string())
}

/// Several devices' marks as one set: per list and title, newest wins.
#[uniffi::export]
pub fn merge_wish(sets: Vec<Vec<WishMark>>) -> Vec<WishMark> {
    let mut by_key: HashMap<(String, String), WishMark> = HashMap::new();
    for m in sets.into_iter().flatten() {
        let key = (m.kind.clone(), m.id.clone());
        if by_key.get(&key).map(|old| m.ts > old.ts).unwrap_or(true) {
            by_key.insert(key, m);
        }
    }
    by_key.into_values().collect()
}

/// Bring this device's wish list for `kind` in line with the marks. A
/// title on the list that no device has marked yet (this list as it was
/// before wish lists were shared) gets an "on" mark. Then the list keeps
/// its order, loses what was taken off elsewhere, and gains what was put
/// on elsewhere, oldest first.
#[uniffi::export]
pub fn sync_wish(marks: Vec<WishMark>, kind: String, list: Vec<String>, now: i64) -> WishSync {
    let mut marks = marks;
    let mut changed = false;
    for id in &list {
        if !marks.iter().any(|m| m.kind == kind && &m.id == id) {
            marks.push(WishMark { kind: kind.clone(), id: id.clone(), on: true, ts: now });
            changed = true;
        }
    }
    let is_on = |id: &String| marks.iter().any(|m| m.kind == kind && &m.id == id && m.on);
    let mut out: Vec<String> = list.iter().filter(|id| is_on(id)).cloned().collect();
    let mut gained: Vec<(i64, String)> = marks.iter()
        .filter(|m| m.kind == kind && m.on && !out.contains(&m.id))
        .map(|m| (m.ts, m.id.clone()))
        .collect();
    gained.sort();
    out.extend(gained.into_iter().map(|(_, id)| id));
    WishSync { marks, list: out, changed }
}

/// Put a title on the list or take it off.
#[uniffi::export]
pub fn mark_wish(marks: Vec<WishMark>, kind: String, id: String, on: bool, now: i64) -> Vec<WishMark> {
    let mut marks: Vec<WishMark> = marks.into_iter()
        .filter(|m| !(m.kind == kind && m.id == id))
        .collect();
    marks.push(WishMark { kind, id, on, ts: now });
    marks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(kind: &str, id: &str, on: bool, ts: i64) -> WishMark {
        WishMark { kind: kind.into(), id: id.into(), on, ts }
    }

    #[test]
    fn reads_the_desktop_format() {
        let json = r#"{"series":{"95403":{"on":true,"ts":100}},"movies":{"782":{"on":false,"ts":200}}}"#;
        let mut got = parse_wish(json.to_string());
        got.sort_by(|a, b| a.id.cmp(&b.id));
        assert_eq!(got, vec![m("movies", "782", false, 200), m("series", "95403", true, 100)]);
        assert_eq!(parse_wish(serialize_wish(got.clone())).len(), 2);
        assert!(parse_wish("not json".into()).is_empty());
    }

    #[test]
    fn a_wish_travels_and_so_does_its_removal() {
        // The desktop wished 2 and later took 1 off; this phone knew 1 and 3.
        let merged = merge_wish(vec![
            vec![m("series", "1", false, 300), m("series", "2", true, 200)],
            vec![m("series", "1", true, 100), m("series", "3", true, 100)],
        ]);
        let s = sync_wish(merged, "series".into(), vec!["1".into(), "3".into()], 999);
        assert_eq!(s.list, vec!["3", "2"], "1 taken off, 2 added at the end");
        assert!(!s.changed, "nothing new to publish");
    }

    #[test]
    fn the_first_sync_publishes_the_old_list() {
        let s = sync_wish(vec![], "movies".into(), vec!["7".into()], 50);
        assert!(s.changed);
        assert_eq!(s.marks, vec![m("movies", "7", true, 50)]);
        let off = mark_wish(s.marks, "movies".into(), "7".into(), false, 60);
        assert_eq!(off, vec![m("movies", "7", false, 60)], "one mark per title");
    }
}
