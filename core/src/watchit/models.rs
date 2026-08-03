// Data models + local-persistence helpers. ListItem is the lightweight catalog
// row; Details is the full per-title record. Both carry serde derives so the
// Kotlin shell can round-trip them to filesDir JSON via the serialize_* /
// parse_* functions below.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct ListItem {
    pub id: String, // TMDB numeric id as string, e.g. "550"
    pub title: String,
    #[serde(default)]
    pub rating: f64,
    #[serde(default)]
    pub year: i32,
    #[serde(default)]
    pub genres: Vec<String>,
    /// "movie" or "tv" — which TMDB endpoint owns this id.
    #[serde(default)]
    pub kind: String,
    /// Poster URL straight from the chart/search response, so list thumbnails
    /// render without a per-title details fetch. Empty if TMDB had no poster.
    #[serde(default)]
    pub poster_url: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct Details {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub year: i32,
    #[serde(default)]
    pub rating: f64,
    #[serde(default)]
    pub votes: i64,
    #[serde(default)]
    pub runtime: String,
    #[serde(default)]
    pub plot: String,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub directors: Vec<String>,
    #[serde(default)]
    pub writers: Vec<String>,
    #[serde(default)]
    pub stars: Vec<String>,
    #[serde(default)]
    pub poster_url: String,
    #[serde(default)]
    pub streaming: Vec<String>,
    #[serde(default)]
    pub content_rating: String,
    #[serde(default)]
    pub country: String,
    /// "Movie" or "TVSeries"
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub release_date: String,
    #[serde(default)]
    pub imdb_id: String,
    #[serde(default)]
    pub start_date: String,
    #[serde(default)]
    pub end_date: String,
    #[serde(default)]
    pub seasons: Option<i32>,
    #[serde(default)]
    pub episodes: Option<i32>,
    #[serde(default)]
    pub popularity: f64,
    #[serde(default)]
    pub error: bool,
}

#[uniffi::export]
pub fn serialize_items(items: Vec<ListItem>) -> String {
    serde_json::to_string(&items).unwrap_or_else(|_| "[]".into())
}

#[uniffi::export]
pub fn parse_items(json: String) -> Vec<ListItem> {
    serde_json::from_str(&json).unwrap_or_default()
}

#[uniffi::export]
pub fn serialize_details(items: Vec<Details>) -> String {
    serde_json::to_string(&items).unwrap_or_else(|_| "[]".into())
}

#[uniffi::export]
pub fn parse_details_list(json: String) -> Vec<Details> {
    serde_json::from_str(&json).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn items_round_trip() {
        let items = vec![
            ListItem { id: "550".into(), title: "Fight Club".into(), rating: 8.4, year: 1999, genres: vec!["Drama".into()], kind: "movie".into(), poster_url: "https://image.tmdb.org/t/p/w500/p.jpg".into() },
            ListItem { id: "1396".into(), title: "Breaking Bad".into(), rating: 8.9, year: 2008, genres: vec!["Drama".into(), "Crime".into()], kind: "tv".into(), poster_url: String::new() },
        ];
        let json = serialize_items(items.clone());
        assert_eq!(parse_items(json), items);
        assert!(parse_items("garbage".into()).is_empty());
    }

    #[test]
    fn details_round_trip_and_optionals() {
        let d = Details {
            id: "1396".into(), title: "Breaking Bad".into(), year: 2008, rating: 8.9,
            kind: "TVSeries".into(), seasons: Some(5), episodes: Some(62),
            genres: vec!["Drama".into()], ..Details::default()
        };
        let json = serialize_details(vec![d.clone()]);
        let back = parse_details_list(json);
        assert_eq!(back.len(), 1);
        assert_eq!(back[0], d);
        assert_eq!(back[0].seasons, Some(5));
    }
}

/// The catalog as the desktop stores it (`~/.watchit/data/list.json`)
/// and as both ends share it. Two lists, because "is this a film or a
/// series" is the one thing neither end wants to re-derive.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct Catalog {
    #[serde(default)]
    pub movies: Vec<ListItem>,
    #[serde(default)]
    pub series: Vec<ListItem>,
}

#[uniffi::export]
pub fn parse_catalog(json: String) -> Catalog {
    serde_json::from_str(&json).unwrap_or_default()
}

#[uniffi::export]
pub fn serialize_catalog(catalog: Catalog) -> String {
    serde_json::to_string_pretty(&catalog).unwrap_or_else(|_| "{}".into())
}

/// Union two catalogs by id, keeping every title either side knows.
///
/// A catalog is a curated pile, not a mirror: each device adds to it
/// separately and neither ever means "and delete everything I do not
/// have". So nothing is dropped, and where both sides know a title the
/// one already held wins, with empty fields backfilled from the other —
/// a row that arrived without a year or a poster should take them from
/// whichever device did the fetch.
#[uniffi::export]
pub fn merge_catalogs(mine: Catalog, theirs: Catalog) -> Catalog {
    Catalog {
        movies: merge_lists(mine.movies, theirs.movies),
        series: merge_lists(mine.series, theirs.series),
    }
}

fn merge_lists(mine: Vec<ListItem>, theirs: Vec<ListItem>) -> Vec<ListItem> {
    use std::collections::HashMap;
    let incoming: HashMap<String, ListItem> =
        theirs.into_iter().map(|i| (i.id.clone(), i)).collect();
    let mut out = mine;
    for it in out.iter_mut() {
        let Some(other) = incoming.get(&it.id) else { continue };
        if it.poster_url.is_empty() { it.poster_url = other.poster_url.clone(); }
        if it.kind.is_empty() { it.kind = other.kind.clone(); }
        if it.year == 0 { it.year = other.year; }
        if it.rating == 0.0 { it.rating = other.rating; }
        if it.genres.is_empty() { it.genres = other.genres.clone(); }
    }
    let held: std::collections::HashSet<String> = out.iter().map(|i| i.id.clone()).collect();
    for (id, it) in incoming {
        if !held.contains(&id) { out.push(it); }
    }
    out
}

#[cfg(test)]
mod catalog_tests {
    use super::*;

    fn item(id: &str, title: &str, year: i32) -> ListItem {
        ListItem { id: id.into(), title: title.into(), year, ..Default::default() }
    }

    #[test]
    fn nothing_either_side_knows_is_lost() {
        let mine = Catalog { movies: vec![item("1", "A", 1990)], series: vec![] };
        let theirs = Catalog {
            movies: vec![item("2", "B", 1991)],
            series: vec![item("3", "C", 1992)],
        };
        let m = merge_catalogs(mine, theirs);
        assert_eq!(m.movies.len(), 2);
        assert_eq!(m.series.len(), 1);
    }

    #[test]
    fn an_empty_field_is_filled_from_the_other_side() {
        let mut mine = item("1", "A", 0);
        mine.poster_url = String::new();
        let mut theirs = item("1", "A", 1990);
        theirs.poster_url = "http://poster".into();
        theirs.kind = "movie".into();
        let m = merge_catalogs(
            Catalog { movies: vec![mine], series: vec![] },
            Catalog { movies: vec![theirs], series: vec![] },
        );
        assert_eq!(m.movies.len(), 1, "same id, one row");
        assert_eq!(m.movies[0].year, 1990);
        assert_eq!(m.movies[0].poster_url, "http://poster");
        assert_eq!(m.movies[0].kind, "movie");
    }
}
