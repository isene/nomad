// Ephemeris: thin UniFFI wrapper over the `orbit` crate. orbit's types live in
// another crate so they can't carry UniFFI derives directly; we mirror them as
// Records here and convert. Compose renders the tables from this structured
// data (we do NOT use orbit's pre-formatted `ephemeris_table` String).

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BodyObs {
    pub name: String,
    pub display: String,
    pub ra_deg: f64,
    pub dec_deg: f64,
    pub distance: f64,
    pub rise: String,
    pub transit: String,
    pub set: String,
    pub rise_h: Option<f64>,
    pub set_h: Option<f64>,
    pub always_up: bool,
    pub never_up: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MoonPhase {
    pub illumination: f64,
    pub phase: f64,
    pub phase_name: String,
    pub symbol: String,
    pub phase_index: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct VisiblePlanet {
    pub name: String,
    pub symbol: String,
    pub color: String,
    pub rise: String,
    pub set: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RiseSet {
    pub rise: String,
    pub set: String,
}

fn conv_body(b: orbit::BodyObs) -> BodyObs {
    BodyObs {
        display: orbit::body_display(b.name).to_string(),
        name: b.name.to_string(),
        ra_deg: b.ra_deg,
        dec_deg: b.dec_deg,
        distance: b.distance,
        rise: b.rise,
        transit: b.transit,
        set: b.set,
        rise_h: b.rise_h,
        set_h: b.set_h,
        always_up: b.always_up,
        never_up: b.never_up,
    }
}

#[uniffi::export]
pub fn all_bodies(year: i32, month: u32, day: u32, lat: f64, lon: f64, tz: f64) -> Vec<BodyObs> {
    orbit::all_bodies(year, month, day, lat, lon, tz)
        .into_iter()
        .map(conv_body)
        .collect()
}

#[uniffi::export]
pub fn moon_phase(year: i32, month: u32, day: u32) -> MoonPhase {
    let m = orbit::moon_phase(year, month, day);
    MoonPhase {
        illumination: m.illumination,
        phase: m.phase,
        phase_name: m.phase_name.to_string(),
        symbol: m.symbol.to_string(),
        phase_index: m.phase_index as u32,
    }
}

#[uniffi::export]
pub fn moon_phase_pct(year: i32, month: u32, day: u32) -> u8 {
    orbit::moon_phase_pct(year, month, day)
}

#[uniffi::export]
pub fn visible_planets(
    year: i32,
    month: u32,
    day: u32,
    lat: f64,
    lon: f64,
    tz: f64,
) -> Vec<VisiblePlanet> {
    orbit::visible_planets(year, month, day, lat, lon, tz)
        .into_iter()
        .map(|p| VisiblePlanet {
            name: p.name.to_string(),
            symbol: p.symbol.to_string(),
            color: p.color.to_string(),
            rise: p.rise,
            set: p.set,
        })
        .collect()
}

#[uniffi::export]
pub fn sun_times(year: i32, month: u32, day: u32, lat: f64, lon: f64, tz: f64) -> Option<RiseSet> {
    orbit::sun_times(year, month, day, lat, lon, tz).map(|(rise, set)| RiseSet { rise, set })
}

#[uniffi::export]
pub fn moon_times(year: i32, month: u32, day: u32, lat: f64, lon: f64, tz: f64) -> Option<RiseSet> {
    orbit::moon_times(year, month, day, lat, lon, tz).map(|(rise, set)| RiseSet { rise, set })
}

#[uniffi::export]
pub fn is_above(
    rise_h: Option<f64>,
    set_h: Option<f64>,
    always_up: bool,
    never_up: bool,
    hour: f64,
) -> bool {
    orbit::is_above(rise_h, set_h, always_up, never_up, hour)
}

/// orbit's human-readable "Tonight: …" summary (moon + visible planets).
#[uniffi::export]
pub fn tonight_summary(
    year: i32,
    month: u32,
    day: u32,
    lat: f64,
    lon: f64,
    tz: f64,
    bortle: f64,
) -> String {
    orbit::tonight_summary(year, month, day, lat, lon, tz, bortle)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Oslo, 2026-05-28, UTC+2.
    const Y: i32 = 2026;
    const M: u32 = 5;
    const D: u32 = 28;
    const LAT: f64 = 59.91;
    const LON: f64 = 10.75;
    const TZ: f64 = 2.0;

    #[test]
    fn bodies_present_and_named() {
        let bodies = all_bodies(Y, M, D, LAT, LON, TZ);
        assert!(!bodies.is_empty());
        // Sun + Moon are always in the set.
        assert!(bodies.iter().any(|b| b.name.eq_ignore_ascii_case("sun")));
        assert!(bodies.iter().any(|b| b.name.eq_ignore_ascii_case("moon")));
        // display names populated.
        assert!(bodies.iter().all(|b| !b.display.is_empty()));
    }

    #[test]
    fn moon_phase_consistent() {
        let mp = moon_phase(Y, M, D);
        assert!(mp.illumination >= 0.0 && mp.illumination <= 1.0);
        assert!(!mp.phase_name.is_empty());
        let pct = moon_phase_pct(Y, M, D);
        assert!(pct <= 100);
    }

    #[test]
    fn sun_rise_before_set_string() {
        let s = sun_times(Y, M, D, LAT, LON, TZ);
        // Oslo in late May has a sun rise/set (not polar day yet).
        assert!(s.is_some());
        let rs = s.unwrap();
        assert!(rs.rise.contains(':') && rs.set.contains(':'));
    }

    #[test]
    fn tonight_summary_nonempty() {
        let s = tonight_summary(Y, M, D, LAT, LON, TZ, 4.0);
        assert!(s.to_lowercase().contains("tonight"));
    }

    #[test]
    fn is_above_matches_orbit() {
        // A body up all day.
        assert!(is_above(None, None, true, false, 12.0));
        assert!(!is_above(None, None, false, true, 12.0));
    }
}
