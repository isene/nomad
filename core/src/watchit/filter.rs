// Filtering, sorting, genre extraction, and catalog merge. Ported from desktop
// watchit's rebuild_filtered/rebuild_genres. Operates on ListItems whose year +
// genres the Kotlin shell has already folded in from the details cache, so this
// stays a pure function of its inputs.

use super::models::ListItem;
use std::collections::HashSet;

fn matches_genres(it: &ListItem, include: &[String], exclude: &[String]) -> bool {
    for inc in include {
        if !it.genres.iter().any(|g| g == inc) { return false; }
    }
    for exc in exclude {
        if it.genres.iter().any(|g| g == exc) { return false; }
    }
    true
}

/// Filter + sort a catalog slice for display. `dump_ids` are hidden;
/// `sort` is "alpha" (title) or anything else (rating, descending).
#[uniffi::export]
pub fn filter_sort(
    items: Vec<ListItem>,
    rating_min: f64,
    year_min: i32,
    year_max: i32,
    genres_include: Vec<String>,
    genres_exclude: Vec<String>,
    dump_ids: Vec<String>,
    sort: String,
) -> Vec<ListItem> {
    let dump: HashSet<&String> = dump_ids.iter().collect();
    let mut out: Vec<ListItem> = items.into_iter()
        .filter(|it| !dump.contains(&it.id))
        .filter(|it| it.rating >= rating_min)
        .filter(|it| year_min == 0 || it.year >= year_min)
        .filter(|it| year_max == 0 || (it.year != 0 && it.year <= year_max))
        .filter(|it| matches_genres(it, &genres_include, &genres_exclude))
        .collect();
    if sort == "alpha" {
        out.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
    } else {
        out.sort_by(|a, b| b.rating.partial_cmp(&a.rating).unwrap_or(std::cmp::Ordering::Equal));
    }
    out
}

/// Order by MY score, best first, unrated last, TMDB rating breaking
/// ties — the desktop's third sort mode, so both ends agree on what "my
/// ratings on top" means.
///
/// Kept apart from `filter_sort` because ratings live in the shared
/// folder rather than in the catalog, and threading them through every
/// filter call for the one sort that needs them would be noise.
#[uniffi::export]
pub fn sort_by_mine(items: Vec<ListItem>, ratings: Vec<crate::watchit::ratings::Rating>) -> Vec<ListItem> {
    use crate::watchit::ratings::{rating_key, score_of};
    // Score each row once, then sort: looking the rating up inside the
    // comparator would repeat the work n log n times over.
    let mut decorated: Vec<(i32, ListItem)> = items.into_iter()
        .map(|it| {
            let score = score_of(&ratings,
                &rating_key(it.id.clone(), it.kind.clone()), &it.title, it.year);
            (score, it)
        })
        .collect();
    decorated.sort_by(|(sa, a), (sb, b)| {
        sb.cmp(sa).then(b.rating.partial_cmp(&a.rating).unwrap_or(std::cmp::Ordering::Equal))
    });
    decorated.into_iter().map(|(_, it)| it).collect()
}

#[cfg(test)]
mod mine_tests {
    use super::*;
    use crate::watchit::ratings::Rating;

    fn item(id: &str, title: &str, kind: &str, rating: f64) -> ListItem {
        ListItem { id: id.into(), title: title.into(), kind: kind.into(), rating, ..Default::default() }
    }

    #[test]
    fn my_scores_lead_and_the_crowd_breaks_ties() {
        let items = vec![
            item("1", "Unrated but adored", "movie", 9.5),
            item("2", "Mine, eight", "movie", 6.0),
            item("3", "Mine, nine", "tv", 5.0),
        ];
        let ratings = vec![
            Rating { id: "2".into(), score: 8, ts: 1, title: "Mine, eight".into(), year: 0 },
            // The series keys with the t prefix, as everywhere else.
            Rating { id: "t3".into(), score: 9, ts: 1, title: "Mine, nine".into(), year: 0 },
        ];
        let sorted = sort_by_mine(items, ratings);
        assert_eq!(sorted[0].id, "3", "my 9 first");
        assert_eq!(sorted[1].id, "2", "then my 8");
        assert_eq!(sorted[2].id, "1", "unrated last, however loved by the crowd");
    }
}

/// Sorted, de-duplicated genre names across the given items.
#[uniffi::export]
pub fn genres_of(items: Vec<ListItem>) -> Vec<String> {
    let mut set: HashSet<String> = HashSet::new();
    for it in &items {
        for g in &it.genres { set.insert(g.clone()); }
    }
    let mut v: Vec<String> = set.into_iter().collect();
    v.sort();
    v
}

/// Append fetched items that aren't already in `existing` (dedup by id),
/// preserving existing order then new arrivals.
#[uniffi::export]
pub fn merge_items(existing: Vec<ListItem>, fetched: Vec<ListItem>) -> Vec<ListItem> {
    // Index the freshly fetched rows so we can both append new ones AND backfill
    // a poster onto a pre-existing row that predates poster capture (so an
    // already-loaded catalog gets thumbnails on the next re-fetch, not just new
    // titles).
    use std::collections::HashMap;
    let fresh: HashMap<&String, &ListItem> = fetched.iter().map(|i| (&i.id, i)).collect();
    let mut out = existing;
    for it in out.iter_mut() {
        if it.poster_url.is_empty() {
            if let Some(f) = fresh.get(&it.id) {
                if !f.poster_url.is_empty() { it.poster_url = f.poster_url.clone(); }
            }
        }
    }
    let mut seen: HashSet<String> = out.iter().map(|i| i.id.clone()).collect();
    for it in fetched {
        if seen.insert(it.id.clone()) { out.push(it); }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, title: &str, rating: f64, year: i32, genres: &[&str]) -> ListItem {
        ListItem {
            id: id.into(), title: title.into(), rating, year,
            genres: genres.iter().map(|s| s.to_string()).collect(),
            kind: "movie".into(),
            poster_url: String::new(),
        }
    }

    fn sample() -> Vec<ListItem> {
        vec![
            item("1", "Alpha", 7.0, 1990, &["Drama"]),
            item("2", "Zeta", 8.5, 2020, &["Action", "Sci-Fi"]),
            item("3", "Mid", 6.0, 2010, &["Comedy"]),
        ]
    }

    #[test]
    fn rating_and_year_filter() {
        let r = filter_sort(sample(), 6.5, 2000, 0, vec![], vec![], vec![], "rating".into());
        // Alpha (7.0 but 1990 < 2000) dropped; Mid (6.0 < 6.5) dropped; only Zeta.
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].id, "2");
    }

    #[test]
    fn dump_hides() {
        let r = filter_sort(sample(), 0.0, 0, 0, vec![], vec![], vec!["2".into()], "rating".into());
        assert!(r.iter().all(|i| i.id != "2"));
    }

    #[test]
    fn genre_include_exclude() {
        let inc = filter_sort(sample(), 0.0, 0, 0, vec!["Action".into()], vec![], vec![], "rating".into());
        assert_eq!(inc.len(), 1);
        assert_eq!(inc[0].id, "2");
        let exc = filter_sort(sample(), 0.0, 0, 0, vec![], vec!["Comedy".into()], vec![], "rating".into());
        assert!(exc.iter().all(|i| i.id != "3"));
    }

    #[test]
    fn sort_modes() {
        let by_rating = filter_sort(sample(), 0.0, 0, 0, vec![], vec![], vec![], "rating".into());
        assert_eq!(by_rating[0].id, "2"); // 8.5 first
        let by_alpha = filter_sort(sample(), 0.0, 0, 0, vec![], vec![], vec![], "alpha".into());
        assert_eq!(by_alpha[0].title, "Alpha");
    }

    #[test]
    fn genres_sorted_unique() {
        let g = genres_of(sample());
        assert_eq!(g, vec!["Action", "Comedy", "Drama", "Sci-Fi"]);
    }

    #[test]
    fn merge_dedups() {
        let existing = vec![item("1", "Alpha", 7.0, 1990, &[])];
        let fetched = vec![item("1", "Alpha", 7.0, 1990, &[]), item("9", "New", 9.0, 2024, &[])];
        let merged = merge_items(existing, fetched);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[1].id, "9");
    }
}
