// TMDB v3 integration, split into pure pieces: URL builders (Kotlin does the
// GET) and JSON-body parsers. Ported from desktop watchit's scrape.rs; the
// parsing logic is identical so mobile and desktop yield the same records.

use super::models::{Details, ListItem};
use serde_json::Value as JsonValue;

const TMDB_BASE: &str = "https://api.themoviedb.org/3";
const POSTER_BASE: &str = "https://image.tmdb.org/t/p/w500";

/// Map a chart name to (endpoint, kind). Names match desktop watchit.
fn chart_to_tmdb(chart: &str) -> Option<(&'static str, &'static str)> {
    Some(match chart {
        "top_rated_movies" | "chart/top" => ("/movie/top_rated", "movie"),
        "top_rated_tv" | "chart/toptv" => ("/tv/top_rated", "tv"),
        "popular_movies" | "chart/moviemeter" => ("/movie/popular", "movie"),
        "popular_tv" | "chart/tvmeter" => ("/tv/popular", "tv"),
        _ => return None,
    })
}

/// "movie" or "tv" for the given chart, so Kotlin can tag parsed items.
#[uniffi::export]
pub fn chart_kind(chart: String) -> Option<String> {
    chart_to_tmdb(&chart).map(|(_, k)| k.to_string())
}

#[uniffi::export]
pub fn tmdb_chart_url(chart: String, page: u32, api_key: String) -> Option<String> {
    let (endpoint, _) = chart_to_tmdb(&chart)?;
    Some(format!("{}{}?api_key={}&page={}", TMDB_BASE, endpoint, api_key, page))
}

#[uniffi::export]
pub fn tmdb_details_url(id: String, kind: String, api_key: String) -> String {
    let k = if kind == "tv" || kind == "TVSeries" { "tv" } else { "movie" };
    format!(
        "{}/{}/{}?api_key={}&append_to_response=credits,external_ids,watch/providers,release_dates,content_ratings",
        TMDB_BASE, k, id, api_key
    )
}

#[uniffi::export]
pub fn tmdb_search_url(query: String, api_key: String) -> String {
    format!(
        "{}/search/multi?api_key={}&query={}&include_adult=false",
        TMDB_BASE, api_key, urlencode(&query)
    )
}

#[uniffi::export]
pub fn tmdb_poster_url(poster_path: String) -> String {
    if poster_path.is_empty() { String::new() } else { format!("{}{}", POSTER_BASE, poster_path) }
}

/// Parse a chart page body into list items. `kind` is "movie" or "tv".
#[uniffi::export]
pub fn parse_chart(json: String, kind: String) -> Vec<ListItem> {
    let Ok(v) = serde_json::from_str::<JsonValue>(&json) else { return Vec::new() };
    let Some(results) = v.get("results").and_then(|a| a.as_array()) else { return Vec::new() };
    let is_movie = kind != "tv";
    results.iter().filter_map(|r| {
        let id = r.get("id").and_then(|x| x.as_i64())?;
        let title_field = if is_movie { "title" } else { "name" };
        let title = r.get(title_field).and_then(|x| x.as_str()).unwrap_or("").to_string();
        let rating = r.get("vote_average").and_then(|x| x.as_f64()).unwrap_or(0.0);
        let date_field = if is_movie { "release_date" } else { "first_air_date" };
        let year = r.get(date_field).and_then(|x| x.as_str())
            .and_then(|s| s.get(..4)).and_then(|s| s.parse().ok()).unwrap_or(0);
        Some(ListItem {
            id: id.to_string(), title, rating, year,
            genres: Vec::new(),
            kind: if is_movie { "movie".into() } else { "tv".into() },
        })
    }).collect()
}

/// Parse the TMDB multi-search body. media_type drives the kind; non
/// movie/tv hits (people, etc.) are dropped.
#[uniffi::export]
pub fn parse_search(json: String) -> Vec<ListItem> {
    let Ok(v) = serde_json::from_str::<JsonValue>(&json) else { return Vec::new() };
    let Some(results) = v.get("results").and_then(|a| a.as_array()) else { return Vec::new() };
    results.iter().filter_map(|r| {
        let mt = r.get("media_type").and_then(|x| x.as_str())?;
        if mt != "movie" && mt != "tv" { return None; }
        let id = r.get("id").and_then(|x| x.as_i64())?;
        let title_field = if mt == "movie" { "title" } else { "name" };
        let title = r.get(title_field).and_then(|x| x.as_str()).unwrap_or("").to_string();
        let date_field = if mt == "movie" { "release_date" } else { "first_air_date" };
        let year = r.get(date_field).and_then(|x| x.as_str())
            .and_then(|s| s.get(..4)).and_then(|s| s.parse().ok()).unwrap_or(0);
        let rating = r.get("vote_average").and_then(|x| x.as_f64()).unwrap_or(0.0);
        Some(ListItem { id: id.to_string(), title, rating, year, genres: Vec::new(), kind: mt.to_string() })
    }).collect()
}

/// Parse a details body. `kind` is "movie" or "tv" (the endpoint that was
/// hit); `region` selects streaming providers + content rating.
#[uniffi::export]
pub fn parse_details(json: String, id: String, kind: String, region: String) -> Details {
    let Ok(v) = serde_json::from_str::<JsonValue>(&json) else {
        return Details { id, error: true, ..Details::default() };
    };
    if v.get("status_code").is_some() {
        return Details { id, error: true, ..Details::default() };
    }
    let is_movie = kind != "tv" && kind != "TVSeries";

    let title = v.get(if is_movie { "title" } else { "name" })
        .and_then(|x| x.as_str()).unwrap_or("").to_string();
    let plot = v.get("overview").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let rating = v.get("vote_average").and_then(|x| x.as_f64()).unwrap_or(0.0);
    let votes = v.get("vote_count").and_then(|x| x.as_i64()).unwrap_or(0);
    let popularity = v.get("popularity").and_then(|x| x.as_f64()).unwrap_or(0.0);

    let runtime = if is_movie {
        v.get("runtime").and_then(|x| x.as_i64()).filter(|&m| m > 0)
            .map(|m| if m >= 60 { format!("{}h {}m", m / 60, m % 60) } else { format!("{}m", m) })
            .unwrap_or_default()
    } else {
        v.get("episode_run_time").and_then(|x| x.as_array()).and_then(|a| a.first())
            .and_then(|x| x.as_i64()).filter(|&m| m > 0)
            .map(|m| format!("{}m", m)).unwrap_or_default()
    };

    let release_date = v.get(if is_movie { "release_date" } else { "first_air_date" })
        .and_then(|x| x.as_str()).unwrap_or("").to_string();
    let year = release_date.get(..4).and_then(|s| s.parse().ok()).unwrap_or(0);
    let (start_date, end_date) = if is_movie {
        (String::new(), String::new())
    } else {
        (release_date.clone(), v.get("last_air_date").and_then(|x| x.as_str()).unwrap_or("").to_string())
    };

    let genres = v.get("genres").and_then(|x| x.as_array())
        .map(|arr| arr.iter().filter_map(|g| g.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
        .unwrap_or_default();

    let directors = if is_movie {
        v.pointer("/credits/crew").and_then(|x| x.as_array())
            .map(|arr| arr.iter()
                .filter(|c| c.get("job").and_then(|j| j.as_str()) == Some("Director"))
                .filter_map(|c| c.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
            .unwrap_or_default()
    } else {
        v.get("created_by").and_then(|x| x.as_array())
            .map(|arr| arr.iter().filter_map(|c| c.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
            .unwrap_or_default()
    };

    let writers = v.pointer("/credits/crew").and_then(|x| x.as_array())
        .map(|arr| arr.iter()
            .filter(|c| c.get("department").and_then(|d| d.as_str()) == Some("Writing"))
            .filter_map(|c| c.get("name").and_then(|n| n.as_str()).map(String::from))
            .collect::<Vec<_>>())
        .map(|mut v| { v.dedup(); v })
        .unwrap_or_default();

    let stars = v.pointer("/credits/cast").and_then(|x| x.as_array())
        .map(|arr| arr.iter().take(10)
            .filter_map(|c| c.get("name").and_then(|n| n.as_str()).map(String::from)).collect())
        .unwrap_or_default();

    let poster_path = v.get("poster_path").and_then(|x| x.as_str()).unwrap_or("");
    let poster_url = if poster_path.is_empty() { String::new() } else { format!("{}{}", POSTER_BASE, poster_path) };

    let seasons = if !is_movie { v.get("number_of_seasons").and_then(|x| x.as_i64()).map(|n| n as i32) } else { None };
    let episodes = if !is_movie { v.get("number_of_episodes").and_then(|x| x.as_i64()).map(|n| n as i32) } else { None };

    let country = v.get("origin_country").and_then(|x| x.as_array())
        .map(|arr| arr.iter().filter_map(|c| c.as_str()).collect::<Vec<_>>().join(","))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| v.get("production_countries").and_then(|x| x.as_array())
            .map(|arr| arr.iter().filter_map(|c| c.get("iso_3166_1").and_then(|s| s.as_str())).collect::<Vec<_>>().join(","))
            .unwrap_or_default());

    let imdb_id = v.pointer("/external_ids/imdb_id").and_then(|x| x.as_str())
        .filter(|s| !s.is_empty()).map(String::from).unwrap_or_default();

    let streaming = if region.is_empty() {
        Vec::new()
    } else {
        // The key is literally "watch/providers" (slash in the name), so a JSON
        // Pointer would split it — use literal key gets instead.
        let region_data = v.get("watch/providers")
            .and_then(|wp| wp.get("results"))
            .and_then(|res| res.get(&region))
            .cloned()
            .unwrap_or(JsonValue::Null);
        let mut out: Vec<String> = Vec::new();
        for bucket in ["flatrate", "free", "ads"] {
            if let Some(arr) = region_data.get(bucket).and_then(|x| x.as_array()) {
                for p in arr {
                    if let Some(n) = p.get("provider_name").and_then(|x| x.as_str()) {
                        if !out.iter().any(|s| s == n) { out.push(n.to_string()); }
                    }
                }
            }
        }
        out
    };

    let content_rating = extract_content_rating(&v, is_movie, &region);

    Details {
        id, title, year, rating, votes, runtime, plot, genres,
        directors, writers, stars, poster_url, streaming, content_rating, country,
        kind: if is_movie { "Movie".into() } else { "TVSeries".into() },
        release_date, imdb_id, start_date, end_date, seasons, episodes, popularity, error: false,
    }
}

fn extract_content_rating(v: &JsonValue, is_movie: bool, region: &str) -> String {
    let regions: Vec<&str> = if region.is_empty() { vec!["US"] } else { vec![region, "US"] };
    if is_movie {
        let Some(results) = v.pointer("/release_dates/results").and_then(|x| x.as_array()) else { return String::new() };
        for want in &regions {
            for r in results {
                if r.get("iso_3166_1").and_then(|x| x.as_str()) != Some(*want) { continue; }
                if let Some(dates) = r.get("release_dates").and_then(|x| x.as_array()) {
                    for d in dates {
                        let cert = d.get("certification").and_then(|x| x.as_str()).unwrap_or("");
                        if !cert.is_empty() { return cert.to_string(); }
                    }
                }
            }
        }
    } else {
        let Some(results) = v.pointer("/content_ratings/results").and_then(|x| x.as_array()) else { return String::new() };
        for want in &regions {
            for r in results {
                if r.get("iso_3166_1").and_then(|x| x.as_str()) != Some(*want) { continue; }
                let cert = r.get("rating").and_then(|x| x.as_str()).unwrap_or("");
                if !cert.is_empty() { return cert.to_string(); }
            }
        }
    }
    String::new()
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for c in s.chars() {
        if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' || c == '~' {
            out.push(c);
        } else if c == ' ' {
            out.push('+');
        } else {
            let mut buf = [0u8; 4];
            for b in c.encode_utf8(&mut buf).as_bytes() {
                out.push_str(&format!("%{:02X}", b));
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_builders() {
        assert_eq!(chart_kind("top_rated_movies".into()).as_deref(), Some("movie"));
        assert_eq!(chart_kind("popular_tv".into()).as_deref(), Some("tv"));
        assert!(chart_kind("nope".into()).is_none());
        assert!(tmdb_chart_url("top_rated_movies".into(), 1, "K".into()).unwrap().contains("/movie/top_rated?api_key=K&page=1"));
        assert!(tmdb_details_url("550".into(), "movie".into(), "K".into()).contains("/movie/550?api_key=K"));
        assert!(tmdb_search_url("fight club".into(), "K".into()).contains("query=fight+club"));
        assert_eq!(tmdb_poster_url("/abc.jpg".into()), "https://image.tmdb.org/t/p/w500/abc.jpg");
        assert_eq!(tmdb_poster_url("".into()), "");
    }

    #[test]
    fn parse_chart_movie() {
        let json = r#"{"results":[
            {"id":550,"title":"Fight Club","vote_average":8.4,"release_date":"1999-10-15"},
            {"id":13,"title":"Forrest Gump","vote_average":8.5,"release_date":"1994-07-06"}
        ]}"#;
        let items = parse_chart(json.into(), "movie".into());
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, "550");
        assert_eq!(items[0].title, "Fight Club");
        assert_eq!(items[0].year, 1999);
        assert_eq!(items[0].kind, "movie");
    }

    #[test]
    fn parse_chart_tv_uses_name() {
        let json = r#"{"results":[{"id":1396,"name":"Breaking Bad","vote_average":8.9,"first_air_date":"2008-01-20"}]}"#;
        let items = parse_chart(json.into(), "tv".into());
        assert_eq!(items[0].title, "Breaking Bad");
        assert_eq!(items[0].year, 2008);
        assert_eq!(items[0].kind, "tv");
    }

    #[test]
    fn parse_search_filters_people() {
        let json = r#"{"results":[
            {"media_type":"movie","id":550,"title":"Fight Club","vote_average":8.4,"release_date":"1999-10-15"},
            {"media_type":"person","id":287,"name":"Brad Pitt"},
            {"media_type":"tv","id":1396,"name":"Breaking Bad","vote_average":8.9,"first_air_date":"2008-01-20"}
        ]}"#;
        let items = parse_search(json.into());
        assert_eq!(items.len(), 2);
        assert!(items.iter().all(|i| i.kind == "movie" || i.kind == "tv"));
    }

    #[test]
    fn parse_details_movie_full() {
        let json = r#"{
            "title":"Fight Club","overview":"A man and Tyler...","vote_average":8.4,"vote_count":27000,
            "popularity":61.4,"runtime":139,"release_date":"1999-10-15","poster_path":"/p.jpg",
            "genres":[{"name":"Drama"}],
            "credits":{"crew":[{"job":"Director","name":"David Fincher","department":"Directing"},
                                {"department":"Writing","name":"Jim Uhls"}],
                       "cast":[{"name":"Brad Pitt"},{"name":"Edward Norton"}]},
            "external_ids":{"imdb_id":"tt0137523"},
            "production_countries":[{"iso_3166_1":"US"}],
            "watch/providers":{"results":{"NO":{"flatrate":[{"provider_name":"Netflix"}]}}},
            "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"R"}]}]}
        }"#;
        let d = parse_details(json.into(), "550".into(), "movie".into(), "NO".into());
        assert!(!d.error);
        assert_eq!(d.title, "Fight Club");
        assert_eq!(d.year, 1999);
        assert_eq!(d.runtime, "2h 19m");
        assert_eq!(d.directors, vec!["David Fincher".to_string()]);
        assert_eq!(d.stars.len(), 2);
        assert_eq!(d.imdb_id, "tt0137523");
        assert_eq!(d.streaming, vec!["Netflix".to_string()]);
        assert_eq!(d.content_rating, "R");
        assert_eq!(d.kind, "Movie");
        assert!(d.poster_url.ends_with("/p.jpg"));
    }

    #[test]
    fn parse_details_404_envelope_is_error() {
        let json = r#"{"status_code":34,"status_message":"not found"}"#;
        let d = parse_details(json.into(), "999".into(), "movie".into(), "".into());
        assert!(d.error);
    }
}
