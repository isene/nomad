// Images: APOD image-URL resolution + starchart URL building, ported from
// astro/src/images.rs. Kotlin GETs the APOD HTML and loads the resolved image
// (and the starchart URL) with Coil; this module only resolves/builds URLs.

/// Resolve the full image URL from the APOD page HTML (apod.nasa.gov/apod/
/// astropix.html). Handles the relative `image/...` form and absolute URLs.
#[uniffi::export]
pub fn resolve_apod_url(html: String) -> Option<String> {
    let src = extract_between(&html, "IMG SRC=\"", "\"")
        .or_else(|| extract_between(&html, "img src=\"", "\""))?;
    let full = if src.starts_with("http") {
        src
    } else {
        format!("https://apod.nasa.gov/apod/{}", src)
    };
    Some(full)
}

/// Build the stelvision starchart image URL for a date/time/location.
#[uniffi::export]
pub fn build_starchart_url(
    year: i32,
    month: u32,
    day: u32,
    hour: u32,
    lat: f64,
    lon: f64,
    tz: f64,
) -> String {
    format!(
        "https://www.stelvision.com/carte-ciel/visu_carte.php?stelmarq=C&mode_affichage=normal&req=stel&date_j_carte={:02}&date_m_carte={:02}&date_a_carte={:04}&heure_h={}&heure_m=00&longi={}&lat={}&tzone={}.0&dst_offset=1&taille_carte=1200&fond_r=255&fond_v=255&fond_b=255&lang=en",
        day, month, year, hour, lon, lat, tz as i32
    )
}

fn extract_between(hay: &str, start: &str, end: &str) -> Option<String> {
    let i = hay.find(start)?;
    let tail = &hay[i + start.len()..];
    let j = tail.find(end)?;
    Some(tail[..j].to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apod_relative_src() {
        let html = r#"<a href="image/2605/foo_big.jpg"><IMG SRC="image/2605/foo.jpg" alt="x"></a>"#;
        assert_eq!(
            resolve_apod_url(html.to_string()).as_deref(),
            Some("https://apod.nasa.gov/apod/image/2605/foo.jpg"),
        );
    }

    #[test]
    fn apod_absolute_and_lowercase() {
        let html = r#"<img src="https://cdn.example/space.jpg">"#;
        assert_eq!(resolve_apod_url(html.to_string()).as_deref(), Some("https://cdn.example/space.jpg"));
    }

    #[test]
    fn apod_none_when_no_image() {
        assert_eq!(resolve_apod_url("<html>no image</html>".to_string()), None);
    }

    #[test]
    fn starchart_url_shape() {
        let u = build_starchart_url(2026, 5, 28, 22, 59.91, 10.75, 2.0);
        assert!(u.contains("date_j_carte=28"));
        assert!(u.contains("date_m_carte=05"));
        assert!(u.contains("date_a_carte=2026"));
        assert!(u.contains("heure_h=22"));
        assert!(u.contains("longi=10.75"));
        assert!(u.contains("lat=59.91"));
        assert!(u.contains("tzone=2.0"));
    }
}
