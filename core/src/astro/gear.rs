// Gear: telescope/eyepiece/misc catalog + optics math, ported from
// astro/src/gear/{optics,data}.rs. The catalog (Store) serializes
// byte-compatibly with desktop astro's ~/.astro/gear.json so the Syncthing-
// shared file round-trips. The TUI-only Config (colors) is intentionally
// dropped — Compose themes itself. Kotlin owns file I/O (to the synced path);
// this module owns the model + math + cross-mode synergy.

use serde::{Deserialize, Serialize};
use std::f64::consts::PI;

// -------------------- catalog model --------------------

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct Telescope {
    pub name: String,
    pub app: f64, // aperture (mm)
    pub tfl: f64, // focal length (mm)
    #[serde(default)]
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct Eyepiece {
    pub name: String,
    pub fl: f64,   // focal length (mm)
    pub afov: f64, // apparent FOV (degrees)
    #[serde(default)]
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct MiscEquipment {
    pub name: String,
    pub kind: String,
    #[serde(default)]
    pub factor: f64,
    #[serde(default)]
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize, uniffi::Record)]
pub struct Store {
    #[serde(default)]
    pub telescopes: Vec<Telescope>,
    #[serde(default)]
    pub eyepieces: Vec<Eyepiece>,
    #[serde(default)]
    pub misc: Vec<MiscEquipment>,
}

#[uniffi::export]
pub fn parse_gear(json: String) -> Store {
    serde_json::from_str(&json).unwrap_or_default()
}

#[uniffi::export]
pub fn serialize_gear(store: Store) -> String {
    serde_json::to_string_pretty(&store).unwrap_or_else(|_| "{}".into())
}

// -------------------- optics formulae (verbatim port) --------------------

fn to_rad(deg: f64) -> f64 {
    deg * PI / 180.0
}
fn to_deg(rad: f64) -> f64 {
    rad * 180.0 / PI
}

fn tfr(app: f64, tfl: f64) -> f64 {
    if app == 0.0 { 0.0 } else { tfl / app }
}
fn mlim(app: f64) -> f64 {
    if app <= 0.0 { 0.0 } else { 5.0 * (app / 10.0).log10() + 7.5 }
}
fn mlim_bortle(app: f64, bortle: f64) -> f64 {
    let dark = mlim(app);
    if dark <= 0.0 {
        return 0.0;
    }
    let b = bortle.clamp(1.0, 9.0);
    let penalty = (b - 3.0).max(0.0) * 0.4;
    (dark - penalty).max(0.0)
}
fn xeye(app: f64) -> f64 {
    app * app / 49.0
}
fn minx(app: f64, tfl: f64) -> f64 {
    let r = tfr(app, tfl);
    if r == 0.0 { 0.0 } else { tfl / (7.0 * r) }
}
fn mine(app: f64, tfl: f64) -> f64 {
    7.0 * tfr(app, tfl)
}
fn maxx(app: f64) -> f64 {
    2.0 * app
}
fn maxe(app: f64, tfl: f64) -> f64 {
    let m = maxx(app);
    if m == 0.0 { 0.0 } else { tfl / m }
}
fn sepr(app: f64) -> f64 {
    if app == 0.0 { 0.0 } else { 3600.0 * to_deg((671e-6 / app).asin()) }
}
fn sepd(app: f64) -> f64 {
    if app == 0.0 { 0.0 } else { 115.824 / app }
}
fn e_st(app: f64, tfl: f64) -> f64 { if app == 0.0 { 0.0 } else { 6.4 * tfl / app } }
fn e_gx(app: f64, tfl: f64) -> f64 { if app == 0.0 { 0.0 } else { 3.6 * tfl / app } }
fn e_pl(app: f64, tfl: f64) -> f64 { if app == 0.0 { 0.0 } else { 2.1 * tfl / app } }
fn e_2s(app: f64, tfl: f64) -> f64 { if app == 0.0 { 0.0 } else { 1.3 * tfl / app } }
fn e_t2(app: f64, tfl: f64) -> f64 { if app == 0.0 { 0.0 } else { 0.7 * tfl / app } }
fn moon_detail(tfl: f64) -> f64 {
    if tfl == 0.0 { 0.0 } else { 384e6 * (to_rad(115.824 / tfl) / 360.0).tan() }
}
fn sun_detail(tfl: f64) -> f64 {
    moon_detail(tfl) / 2.5668
}
fn magx(tfl: f64, epfl: f64) -> f64 {
    if epfl == 0.0 { 0.0 } else { tfl / epfl }
}
fn tfov(tfl: f64, epfl: f64, afov: f64) -> f64 {
    let m = magx(tfl, epfl);
    if m == 0.0 { 0.0 } else { afov / m }
}
fn pupl(app: f64, tfl: f64, epfl: f64) -> f64 {
    let m = magx(tfl, epfl);
    if m == 0.0 { 0.0 } else { app / m }
}

// -------------------- computed records (for the UI) --------------------

/// Per-telescope derived figures.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScopeCalcs {
    pub focal_ratio: f64,
    pub mag_limit: f64,
    pub mag_limit_bortle: f64,
    pub light_gathering: f64, // times naked eye
    pub min_mag: f64,
    pub min_eyepiece_fl: f64,
    pub max_mag: f64,
    pub max_eyepiece_fl: f64,
    pub sep_rayleigh: f64,
    pub sep_dawes: f64,
    pub ideal_starfield_fl: f64,
    pub ideal_galaxy_fl: f64,
    pub ideal_planet_fl: f64,
    pub ideal_double_fl: f64,
    pub ideal_tightdouble_fl: f64,
    pub moon_detail_km: f64,
    pub sun_detail_km: f64,
}

#[uniffi::export]
pub fn scope_calcs(app: f64, tfl: f64, bortle: f64) -> ScopeCalcs {
    ScopeCalcs {
        focal_ratio: tfr(app, tfl),
        mag_limit: mlim(app),
        mag_limit_bortle: mlim_bortle(app, bortle),
        light_gathering: xeye(app),
        min_mag: minx(app, tfl),
        min_eyepiece_fl: mine(app, tfl),
        max_mag: maxx(app),
        max_eyepiece_fl: maxe(app, tfl),
        sep_rayleigh: sepr(app),
        sep_dawes: sepd(app),
        ideal_starfield_fl: e_st(app, tfl),
        ideal_galaxy_fl: e_gx(app, tfl),
        ideal_planet_fl: e_pl(app, tfl),
        ideal_double_fl: e_2s(app, tfl),
        ideal_tightdouble_fl: e_t2(app, tfl),
        moon_detail_km: moon_detail(tfl),
        sun_detail_km: sun_detail(tfl),
    }
}

/// Per (telescope, eyepiece) figures + target-class suitability (mutually
/// exclusive across the exit-pupil ladder).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EyepieceCalcs {
    pub magnification: f64,
    pub true_fov: f64,
    pub exit_pupil: f64,
    pub rich_field: bool,   // exit pupil > 6
    pub galaxy: bool,       // 3-6
    pub planet: bool,       // 1.5-3
    pub double: bool,       // 1-1.5
    pub tight_double: bool, // < 1
}

#[uniffi::export]
pub fn eyepiece_calcs(app: f64, tfl: f64, epfl: f64, afov: f64) -> EyepieceCalcs {
    let p = if app <= 0.0 || epfl <= 0.0 || tfl <= 0.0 { 0.0 } else { pupl(app, tfl, epfl) };
    let usable = app > 0.0 && epfl > 0.0 && tfl > 0.0;
    EyepieceCalcs {
        magnification: magx(tfl, epfl),
        true_fov: tfov(tfl, epfl, afov),
        exit_pupil: p,
        rich_field: usable && p > 6.0,
        galaxy: usable && p > 3.0 && p <= 6.0,
        planet: usable && p > 1.5 && p <= 3.0,
        double: usable && p >= 1.0 && p <= 1.5,
        tight_double: usable && p < 1.0,
    }
}

// -------------------- cross-mode synergy --------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TargetClass {
    StarField,
    Galaxy,
    Planet,
    Double,
    TightDouble,
}

#[uniffi::export]
pub fn ideal_eyepiece_fl(app: f64, tfl: f64, target: TargetClass) -> f64 {
    match target {
        TargetClass::StarField => e_st(app, tfl),
        TargetClass::Galaxy => e_gx(app, tfl),
        TargetClass::Planet => e_pl(app, tfl),
        TargetClass::Double => e_2s(app, tfl),
        TargetClass::TightDouble => e_t2(app, tfl),
    }
}

/// Index into `eyepieces` of the one whose focal length is closest to the
/// ideal for `target` with the given scope. None if there are no eyepieces.
#[uniffi::export]
pub fn best_eyepiece(store: Store, telescope: Telescope, target: TargetClass) -> Option<u32> {
    if store.eyepieces.is_empty() {
        return None;
    }
    let ideal = ideal_eyepiece_fl(telescope.app, telescope.tfl, target);
    let mut best = 0usize;
    let mut best_d = f64::INFINITY;
    for (i, ep) in store.eyepieces.iter().enumerate() {
        let d = (ep.fl - ideal).abs();
        if d < best_d {
            best_d = d;
            best = i;
        }
    }
    Some(best as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    // An 8" f/6 Dobsonian: aperture 203mm, focal length 1219mm.
    const APP: f64 = 203.0;
    const TFL: f64 = 1219.0;

    #[test]
    fn scope_calcs_sane() {
        let c = scope_calcs(APP, TFL, 4.0);
        assert!((c.focal_ratio - (TFL / APP)).abs() < 1e-9);
        assert!(c.max_mag == 2.0 * APP);
        assert!(c.mag_limit > c.mag_limit_bortle); // bortle 4 costs limiting mag
        assert!(c.light_gathering > 800.0 && c.light_gathering < 900.0);
        assert!(c.sep_dawes > 0.0 && c.sep_dawes < 1.0);
    }

    #[test]
    fn eyepiece_calcs_and_suitability() {
        // 25mm eyepiece, 50° AFOV.
        let e = eyepiece_calcs(APP, TFL, 25.0, 50.0);
        assert!((e.magnification - (TFL / 25.0)).abs() < 1e-9);
        assert!(e.exit_pupil > 4.0 && e.exit_pupil < 5.0); // ~4.16mm -> galaxy band
        assert!(e.galaxy);
        assert!(!e.planet && !e.rich_field);
        // exactly one band true
        let n = [e.rich_field, e.galaxy, e.planet, e.double, e.tight_double]
            .iter().filter(|&&b| b).count();
        assert_eq!(n, 1);
    }

    #[test]
    fn gear_round_trip() {
        let store = Store {
            telescopes: vec![Telescope { name: "Dob 8".into(), app: APP, tfl: TFL, notes: "".into() }],
            eyepieces: vec![
                Eyepiece { name: "25mm".into(), fl: 25.0, afov: 50.0, notes: "".into() },
                Eyepiece { name: "10mm".into(), fl: 10.0, afov: 52.0, notes: "".into() },
                Eyepiece { name: "6mm".into(), fl: 6.0, afov: 52.0, notes: "".into() },
            ],
            misc: vec![MiscEquipment { name: "2x Barlow".into(), kind: "barlow".into(), factor: 2.0, notes: "".into() }],
        };
        let json = serialize_gear(store.clone());
        assert_eq!(parse_gear(json), store);
    }

    #[test]
    fn best_eyepiece_for_planets_picks_short_fl() {
        let store = Store {
            telescopes: vec![],
            eyepieces: vec![
                Eyepiece { name: "25mm".into(), fl: 25.0, afov: 50.0, notes: "".into() },
                Eyepiece { name: "10mm".into(), fl: 10.0, afov: 52.0, notes: "".into() },
                Eyepiece { name: "6mm".into(), fl: 6.0, afov: 52.0, notes: "".into() },
            ],
            misc: vec![],
        };
        let scope = Telescope { name: "Dob 8".into(), app: APP, tfl: TFL, notes: "".into() };
        // Planets want a small exit pupil -> short eyepiece. ideal e_pl ~ 2.1*tfl/app ~ 12.6mm
        let idx = best_eyepiece(store, scope, TargetClass::Planet).unwrap();
        assert_eq!(idx, 1); // 10mm is closest to ~12.6mm
    }

    #[test]
    fn parse_gear_tolerates_missing_optional_fields() {
        let json = r#"{"telescopes":[{"name":"T","app":100.0,"tfl":500.0}],"eyepieces":[],"misc":[]}"#;
        let s = parse_gear(json.into());
        assert_eq!(s.telescopes.len(), 1);
        assert_eq!(s.telescopes[0].notes, "");
    }
}
