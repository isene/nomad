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
            ListItem { id: "550".into(), title: "Fight Club".into(), rating: 8.4, year: 1999, genres: vec!["Drama".into()], kind: "movie".into() },
            ListItem { id: "1396".into(), title: "Breaking Bad".into(), rating: 8.9, year: 2008, genres: vec!["Drama".into(), "Crime".into()], kind: "tv".into() },
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
