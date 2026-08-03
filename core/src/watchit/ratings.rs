// My own 1-10 score for a title, shared with the desktop watchit TUI.
//
// The wire format is desktop watchit's `~/.watchit/sync/ratings-*.json`:
// a JSON object keyed by catalog id, `{"278": {"score": 9, "ts": ...,
// "title": "...", "year": 1994}}`. One file per device, because the
// folder is a Syncthing share and two writers on one file is what
// leaves `.sync-conflict-` copies nobody reads. Every device reads them
// all and writes only its own; newest timestamp per title wins.
//
// Two details that look like over-engineering and are not:
//   * A cleared rating is score 0 with a fresh timestamp, not a deleted
//     entry — otherwise the other device's older rating wins the merge
//     and the rating comes back.
//   * Each entry carries its title and year, because the two ends do
//     NOT share an id space: the phone keys by TMDB id while a desktop
//     catalog imported from IMDB-terminal still holds `tt…` ids. Title
//     plus year is the only key both can agree on.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
struct Stored {
    #[serde(default)]
    score: u8,
    #[serde(default)]
    ts: i64,
    #[serde(default)]
    title: String,
    #[serde(default)]
    year: i32,
}

/// One rating, flattened for the FFI (the wire format is a map; UniFFI
/// records are easier to hand Kotlin as a list).
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct Rating {
    pub id: String,
    /// 1-10, or 0 for "no rating" (a tombstone, not an absence).
    pub score: i32,
    /// Unix seconds.
    pub ts: i64,
    pub title: String,
    pub year: i32,
}

/// A title reduced to what identifies it. Case and punctuation are not
/// signal, and neither is the year range the desktop's old IMDB import
/// baked into the title itself ("Travelers (2016-2018)") — without
/// stripping that, an imported row never matches its TMDB twin and the
/// rating made here never reaches it.
fn title_key(title: &str) -> String {
    let mut t = title.trim();
    if t.ends_with(')') {
        if let Some(open) = t.rfind('(') {
            let inner = &t[open + 1..t.len() - 1];
            let only_years = !inner.is_empty() && inner.chars().all(|c| {
                c.is_ascii_digit() || c == '-' || c == '\u{2013}' || c == '\u{2014}'
                    || c == ' ' || c == '/' || c == '?'
            });
            if only_years { t = t[..open].trim_end(); }
        }
    }
    t.to_lowercase().chars().filter(|c| c.is_alphanumeric()).collect()
}

/// Same title? A year of 0 means "unknown" — the desktop's imported rows
/// carry no year — and first-air vs release year differ often enough to
/// allow a year of slack.
fn same_year(a: i32, b: i32) -> bool {
    a == 0 || b == 0 || (a - b).abs() <= 1
}

/// Parse one device's ratings file. Unreadable JSON yields nothing —
/// a half-synced file must never take the app down.
#[uniffi::export]
pub fn parse_ratings(json: String) -> Vec<Rating> {
    let map: HashMap<String, Stored> = serde_json::from_str(&json).unwrap_or_default();
    map.into_iter().map(|(id, s)| Rating {
        id, score: s.score as i32, ts: s.ts, title: s.title, year: s.year,
    }).collect()
}

/// Serialize back into the desktop's map-keyed-by-id format.
#[uniffi::export]
pub fn serialize_ratings(ratings: Vec<Rating>) -> String {
    let map: HashMap<String, Stored> = ratings.into_iter().map(|r| (
        r.id,
        Stored { score: r.score.clamp(0, 10) as u8, ts: r.ts, title: r.title, year: r.year },
    )).collect();
    serde_json::to_string_pretty(&map).unwrap_or_else(|_| "{}".to_string())
}

/// Merge several devices' ratings into one set: newest timestamp wins,
/// per id AND per title+year (the same film under two id schemes is one
/// rating, not two).
#[uniffi::export]
pub fn merge_ratings(sets: Vec<Vec<Rating>>) -> Vec<Rating> {
    let mut by_id: HashMap<String, Rating> = HashMap::new();
    // id chosen for a given title, so the second scheme folds into the first.
    let mut by_title: HashMap<String, Vec<(i32, String)>> = HashMap::new();
    for set in sets {
        for r in set {
            let key = if by_id.contains_key(&r.id) {
                r.id.clone()
            } else {
                by_title.get(&title_key(&r.title))
                    .filter(|_| !r.title.is_empty())
                    .and_then(|v| v.iter()
                        .find(|(y, _)| *y == r.year)
                        .or_else(|| v.iter().find(|(y, _)| same_year(*y, r.year))))
                    .map(|(_, id)| id.clone())
                    .unwrap_or_else(|| r.id.clone())
            };
            // On a tie the TMDB-keyed id wins: the desktop is migrating
            // off IMDB tconsts, and a device that has not synced since
            // must not drag the old scheme back into the shared folder.
            let newer = by_id.get(&key)
                .map(|old| r.ts > old.ts
                    || (r.ts == old.ts && key.starts_with("tt") && !r.id.starts_with("tt")))
                .unwrap_or(true);
            if newer {
                let winner = if key.starts_with("tt") && !r.id.starts_with("tt") {
                    by_id.remove(&key);
                    r.id.clone()
                } else {
                    key
                };
                if !r.title.is_empty() {
                    let slot = by_title.entry(title_key(&r.title)).or_default();
                    slot.retain(|(_, id)| *id != winner);
                    slot.push((r.year, winner.clone()));
                }
                by_id.insert(winner, r);
            }
        }
    }
    let mut out: Vec<Rating> = by_id.into_values().collect();
    out.sort_by(|a, b| b.score.cmp(&a.score).then(a.title.cmp(&b.title)));
    out
}

/// My score for a title: by id, else by title+year. 0 means unrated.
#[uniffi::export]
pub fn rating_for(ratings: Vec<Rating>, id: String, title: String, year: i32) -> i32 {
    if let Some(r) = ratings.iter().find(|r| r.id == id) {
        return r.score;
    }
    let want = title_key(&title);
    let by_title: Vec<&Rating> = ratings.iter()
        .filter(|r| !r.title.is_empty() && title_key(&r.title) == want)
        .collect();
    by_title.iter().find(|r| r.year == year)
        .or_else(|| by_title.iter().find(|r| same_year(r.year, year)))
        .map(|r| r.score)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(id: &str, score: i32, ts: i64, title: &str, year: i32) -> Rating {
        Rating { id: id.into(), score, ts, title: title.into(), year }
    }

    #[test]
    fn round_trips_the_desktop_format() {
        let json = r#"{"278":{"score":9,"ts":1700,"title":"The Shawshank Redemption","year":1994}}"#;
        let parsed = parse_ratings(json.to_string());
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].score, 9);
        assert_eq!(parsed[0].id, "278");
        let again = parse_ratings(serialize_ratings(parsed));
        assert_eq!(again[0].title, "The Shawshank Redemption");
    }

    #[test]
    fn garbage_is_not_fatal() {
        assert!(parse_ratings("not json at all".into()).is_empty());
    }

    #[test]
    fn newest_wins_and_a_clear_is_newest() {
        let merged = merge_ratings(vec![
            vec![r("278", 9, 100, "Shawshank", 1994)],
            vec![r("278", 0, 200, "Shawshank", 1994)],
        ]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].score, 0, "the clear is newer, so it sticks");
    }

    #[test]
    fn an_imported_title_still_finds_its_rating() {
        // The desktop's imported rows carry no year and wear the year
        // range in the title; the phone keys the same show by TMDB id.
        let merged = merge_ratings(vec![vec![r("71914", 8, 100, "The Wheel of Time", 2021)]]);
        assert_eq!(rating_for(merged.clone(), "tt7462410".into(),
            "The Wheel of Time".into(), 0), 8);
        assert_eq!(rating_for(merged, "tt2261227".into(),
            "Altered Carbon (2018\u{2013}2020)".into(), 0), 0, "different show, no match");
    }

    #[test]
    fn a_stale_device_does_not_resurrect_the_old_ids() {
        // This phone last synced before the desktop migrated off IMDB
        // ids, so its own file still keys everything by tconst. Merging
        // must fold those into their TMDB twins rather than double them.
        let merged = merge_ratings(vec![
            vec![r("tt11126994", 9, 500, "Arcane: League of Legends", 2021)],
            vec![r("94605", 9, 500, "Arcane: League of Legends", 2021)],
        ]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].id, "94605", "kept under the TMDB id");
    }

    #[test]
    fn one_film_under_two_id_schemes_is_one_rating() {
        let merged = merge_ratings(vec![
            vec![r("tt0111161", 7, 100, "The Shawshank Redemption", 1994)],
            vec![r("278", 9, 200, "the shawshank redemption", 1994)],
        ]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].score, 9);
        // And it is findable under either id.
        assert_eq!(rating_for(merged.clone(), "278".into(), String::new(), 0), 9);
        assert_eq!(
            rating_for(merged, "tt0111161".into(), "The Shawshank Redemption".into(), 1994),
            9,
        );
    }
}
