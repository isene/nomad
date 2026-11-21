// The astronomy clock (nomad orrery): the sky over the next 24 hours for
// the dial, the sky right now, and what happens in the weeks ahead. All of
// it comes from orbit on the phone, with no network.

use orbit::astro as a;

/// Seconds between two samples of a height.
const STEP: i64 = 600;
/// Samples the dial draws: 24 hours, both ends.
const DRAWN: usize = 145;
/// Samples searched for rises and sets: 30 hours, so a night that starts
/// late in the 24 still has its end.
const SEARCHED: usize = 181;

/// The bodies of the clock, in the order the dial draws their tracks.
const BODIES: [&str; 7] = ["sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn"];

/// One body over the next 24 hours: its height every ten minutes, and the
/// first rise, set and highest point (unix seconds) in that time.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockTrack {
    pub name: String,
    pub alt: Vec<f64>,
    pub rise: Option<i64>,
    pub set: Option<i64>,
    pub transit: Option<i64>,
    pub transit_alt: f64,
}

/// A moment on the dial: "sunrise", "sunset", "civil dawn", "civil dusk",
/// "nautical dawn", "nautical dusk", "astro dawn", "astro dusk",
/// "moonrise" or "moonset".
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockMark {
    pub kind: String,
    pub at: i64,
}

/// The next 24 hours. `start` is the first sample, `step` the seconds
/// between samples. `dark_start` and `dark_end` bound the next full
/// night (the sun 18° or more under the horizon); both are None when the
/// sun never gets that low. `moonless` is the minutes of that night with
/// the moon down. `deepest` is the sun's lowest height in the 24 hours.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockDay {
    pub start: i64,
    pub step: i64,
    pub tracks: Vec<ClockTrack>,
    pub marks: Vec<ClockMark>,
    pub dark_start: Option<i64>,
    pub dark_end: Option<i64>,
    pub moonless: i64,
    pub deepest: f64,
}

/// A body right now: height (with the lift of the air) and compass
/// direction in degrees, brightness, constellation, and its place along
/// the sun's path (`ecl_lon`, degrees).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockBody {
    pub name: String,
    pub alt: f64,
    pub az: f64,
    pub mag: f64,
    pub con: String,
    pub ecl_lon: f64,
}

/// The sky right now. `sidereal` is the local star time in hours.
/// `moon_phase` is the part of the month gone (0 new, 0.5 full).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockNow {
    pub sidereal: f64,
    pub bodies: Vec<ClockBody>,
    pub moon_lit: f64,
    pub moon_age: f64,
    pub moon_phase: f64,
    pub moon_name: String,
}

/// Something ahead: `kind` is "moon", "eclipse", "season", "shower",
/// "planet" or "pair" (two planets close together).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ClockEvent {
    pub at: i64,
    pub kind: String,
    pub text: String,
}

fn unix_of(j: f64) -> i64 {
    ((j - 2440587.5) * 86400.0).round() as i64
}

/// The moment between two samples where the height passes `h`.
fn cross(t0: i64, a0: f64, a1: f64, h: f64) -> i64 {
    t0 + ((h - a0) / (a1 - a0) * STEP as f64).round() as i64
}

/// Every pass of the height `h` in the samples: (unix seconds, rising).
fn passes(start: i64, alt: &[f64], h: f64) -> Vec<(i64, bool)> {
    let mut out = Vec::new();
    for i in 1..alt.len() {
        let (a0, a1) = (alt[i - 1], alt[i]);
        let t0 = start + (i as i64 - 1) * STEP;
        if a0 < h && a1 >= h {
            out.push((cross(t0, a0, a1, h), true));
        } else if a0 >= h && a1 < h {
            out.push((cross(t0, a0, a1, h), false));
        }
    }
    out
}

/// The first highest point inside the samples, fitted through its two
/// neighbours: (unix seconds, height).
fn peak(start: i64, alt: &[f64]) -> Option<(i64, f64)> {
    (1..alt.len() - 1).find(|&i| alt[i - 1] < alt[i] && alt[i] >= alt[i + 1]).map(|i| {
        let (y0, y1, y2) = (alt[i - 1], alt[i], alt[i + 1]);
        let den = y0 - 2.0 * y1 + y2;
        let off = if den.abs() < 1e-12 { 0.0 } else { 0.5 * (y0 - y2) / den };
        let t = start + i as i64 * STEP + (off * STEP as f64).round() as i64;
        (t, y1 - 0.25 * (y0 - y2) * off)
    })
}

/// The heights of the clock's bodies every ten minutes from `start`.
fn heights(start: i64, lat: f64, lon: f64, tz: f64) -> Vec<Vec<f64>> {
    let mut out = vec![Vec::with_capacity(SEARCHED); BODIES.len()];
    for i in 0..SEARCHED {
        let s = a::sky_jd(a::jd_of_unix(start + i as i64 * STEP), lat, lon, tz);
        for (k, n) in BODIES.iter().enumerate() {
            out[k].push(a::where_is(&s, n).alt);
        }
    }
    out
}

/// The sky over the next 24 hours from `unix`, at a place (`tz` in hours).
#[uniffi::export]
pub fn clock_day(unix: i64, lat: f64, lon: f64, tz: f64) -> ClockDay {
    let start = unix - unix.rem_euclid(STEP);
    let end = start + (DRAWN as i64 - 1) * STEP;
    let all = heights(start, lat, lon, tz);
    let in_day = |t: i64| t >= start && t <= end;

    let mut tracks = Vec::new();
    for (k, n) in BODIES.iter().enumerate() {
        let alt = &all[k];
        let p = passes(start, alt, a::rise_height(n));
        let first = |up: bool| p.iter().find(|x| x.1 == up && in_day(x.0)).map(|x| x.0);
        let top = peak(start, &alt[..DRAWN]);
        tracks.push(ClockTrack {
            name: n.to_string(),
            alt: alt[..DRAWN].to_vec(),
            rise: first(true),
            set: first(false),
            transit: top.map(|t| t.0),
            transit_alt: top.map_or(f64::NAN, |t| t.1),
        });
    }

    let mut marks = Vec::new();
    let sun = &all[0];
    for (h, up, down) in [
        (a::rise_height("sun"), "sunrise", "sunset"),
        (-6.0, "civil dawn", "civil dusk"),
        (-12.0, "nautical dawn", "nautical dusk"),
        (-18.0, "astro dawn", "astro dusk"),
    ] {
        for (t, rising) in passes(start, sun, h) {
            if in_day(t) {
                marks.push(ClockMark { kind: (if rising { up } else { down }).to_string(), at: t });
            }
        }
    }
    for (t, rising) in passes(start, &all[1], a::rise_height("moon")) {
        if in_day(t) {
            marks.push(ClockMark { kind: (if rising { "moonrise" } else { "moonset" }).to_string(), at: t });
        }
    }
    marks.sort_by_key(|m| m.at);

    // The next full night: now, if it is dark already, or the next dusk.
    let deep = passes(start, sun, -18.0);
    let dark_start = if sun[0] < -18.0 {
        Some(start)
    } else {
        deep.iter().find(|x| !x.1 && in_day(x.0)).map(|x| x.0)
    };
    let dark_end = dark_start.and_then(|d| deep.iter().find(|x| x.1 && x.0 > d).map(|x| x.0));
    let moonless = match (dark_start, dark_end) {
        (Some(d0), Some(d1)) => {
            let mut sum = 0;
            for i in 0..SEARCHED {
                let t = start + i as i64 * STEP;
                if t >= d0 && t < d1 && all[1][i] < a::rise_height("moon") {
                    sum += STEP / 60;
                }
            }
            sum
        }
        _ => 0,
    };
    let deepest = sun[..DRAWN].iter().cloned().fold(f64::INFINITY, f64::min);

    ClockDay { start, step: STEP, tracks, marks, dark_start, dark_end, moonless, deepest }
}

/// The moon's phase by name; the four main ones last half a day either
/// side (the moon gains about 6° on the sun in half a day).
fn moon_name(f: f64) -> &'static str {
    let near = |c: f64| {
        let d = (f - c).abs();
        d < 0.017 || d > 0.983
    };
    if near(0.0) {
        "New moon"
    } else if near(0.25) {
        "First quarter"
    } else if near(0.5) {
        "Full moon"
    } else if near(0.75) {
        "Last quarter"
    } else if f < 0.25 {
        "Waxing crescent"
    } else if f < 0.5 {
        "Waxing gibbous"
    } else if f < 0.75 {
        "Waning gibbous"
    } else {
        "Waning crescent"
    }
}

/// The sky at the moment `unix`, at a place (`tz` in hours).
#[uniffi::export]
pub fn clock_now(unix: i64, lat: f64, lon: f64, tz: f64) -> ClockNow {
    let j = a::jd_of_unix(unix);
    let s = a::sky_jd(j, lat, lon, tz);
    let bodies = BODIES
        .iter()
        .map(|n| {
            let p = a::pos(&s, n);
            let h = a::horizon(&s, p.ra, p.dec);
            ClockBody {
                name: n.to_string(),
                alt: h.alt + a::refraction(h.alt),
                az: h.az,
                mag: a::magnitude(&s, n),
                con: a::constellation(p.gra, p.gdec, j).to_string(),
                ecl_lon: if *n == "sun" { s.sun_lon } else { p.lon },
            }
        })
        .collect::<Vec<ClockBody>>();
    let age = a::moon_age(j);
    // The part of the month gone, from how far the moon is ahead of the sun.
    let phase = (bodies[1].ecl_lon - bodies[0].ecl_lon).rem_euclid(360.0) / 360.0;
    ClockNow {
        sidereal: a::sidereal(&s) / 15.0,
        bodies,
        moon_lit: a::moon_lit(&s),
        moon_age: age,
        moon_phase: phase,
        moon_name: moon_name(phase).to_string(),
    }
}

/// What kind of almanac line this is, and the line without its sign.
fn sort_line(text: &str) -> (&'static str, String) {
    let plain = match text.split_once(' ') {
        Some((head, rest)) if !head.chars().any(|c| c.is_alphanumeric()) => rest.to_string(),
        _ => text.to_string(),
    };
    let kind = if plain.contains("eclipse") {
        "eclipse"
    } else if plain.contains("equinox") || plain.contains("solstice") {
        "season"
    } else if plain.contains("peak") {
        "shower"
    } else if plain.starts_with("New Moon")
        || plain.starts_with("Full Moon")
        || plain.contains("Quarter")
        || plain.starts_with("Moon ")
    {
        "moon"
    } else {
        "planet"
    };
    (kind, plain)
}

/// What happens in the sky from `unix` to `days` later, in order: moon
/// phases, eclipses, seasons, meteor showers, planets opposite the sun or
/// farthest from it, and two bright planets within 4° of each other.
#[uniffi::export]
pub fn clock_events(unix: i64, days: u32) -> Vec<ClockEvent> {
    let j0 = a::jd_of_unix(unix);
    let j1 = j0 + days as f64;
    let y0 = a::date_of(j0).0;
    let y1 = a::date_of(j1).0;
    let mut out = Vec::new();
    for y in y0..=y1 {
        for e in a::almanac(y) {
            if e.when >= j0 && e.when < j1 {
                let (kind, text) = sort_line(&e.text);
                out.push(ClockEvent { at: unix_of(e.when), kind: kind.to_string(), text });
            }
        }
    }
    let bright = ["mercury", "venus", "mars", "jupiter", "saturn"];
    for i in 0..bright.len() {
        for k in i + 1..bright.len() {
            let (p, q) = (bright[i], bright[k]);
            let mut from = j0;
            while let Some(t) = a::next_conjunction(p, q, from, j1 - from) {
                let s = a::sky_jd(t, 0.0, 0.0, 0.0);
                let (pp, qq) = (a::pos(&s, p), a::pos(&s, q));
                let apart = a::separation(pp.gra, pp.gdec, qq.gra, qq.gdec);
                // Two planets beside the sun are lost in its light.
                if apart < 4.0 && a::elongation(&s, p) > 15.0 {
                    out.push(ClockEvent {
                        at: unix_of(t),
                        kind: "pair".to_string(),
                        text: format!("{} and {} {}° apart", a::title(p), a::title(q), a::fixed(apart, 1)),
                    });
                }
                from = t + 2.0;
                if from >= j1 {
                    break;
                }
            }
        }
    }
    out.sort_by_key(|e| e.at);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    // Greenwich, in winter time.
    const LAT: f64 = 51.48;
    const LON: f64 = 0.0;

    fn unix(y: i32, m: u32, d: u32, h: u32) -> i64 {
        unix_of(a::jd_at(y, m, d, h, 0, 0))
    }

    fn mark(day: &ClockDay, kind: &str) -> i64 {
        day.marks.iter().find(|m| m.kind == kind).unwrap_or_else(|| panic!("no {kind}")).at
    }

    #[test]
    fn a_winter_day_at_greenwich() {
        let day = clock_day(unix(2026, 12, 21, 0), LAT, LON, 0.0);
        assert_eq!(day.tracks.len(), 7);
        assert!(day.tracks.iter().all(|t| t.alt.len() == DRAWN));
        // Sunrise about 08:04 and sunset about 15:54, UT.
        let rise = mark(&day, "sunrise") - unix(2026, 12, 21, 0);
        let set = mark(&day, "sunset") - unix(2026, 12, 21, 0);
        assert!((rise - (8 * 3600 + 4 * 60)).abs() < 5 * 60, "sunrise {rise}");
        assert!((set - (15 * 3600 + 54 * 60)).abs() < 5 * 60, "sunset {set}");
        // Noon: the sun about 15° up at 12:00.
        let sun = &day.tracks[0];
        assert!((sun.transit.unwrap() - unix(2026, 12, 21, 12)).abs() < 10 * 60);
        assert!((sun.transit_alt - 15.0).abs() < 1.0, "noon height {}", sun.transit_alt);
        // Dark already at midnight.
        assert_eq!(day.dark_start, Some(day.start));
        // From noon, full night from about 17:58 to 06:00.
        let day = clock_day(unix(2026, 12, 21, 12), LAT, LON, 0.0);
        let d0 = day.dark_start.unwrap() - unix(2026, 12, 21, 0);
        assert!((d0 - (17 * 3600 + 58 * 60)).abs() < 10 * 60, "dusk {d0}");
        assert!(day.dark_end.unwrap() > day.dark_start.unwrap() + 11 * 3600);
        assert!(day.moonless >= 0 && day.moonless <= 14 * 60);
    }

    #[test]
    fn a_summer_night_in_london_never_gets_fully_dark() {
        let day = clock_day(unix(2026, 6, 21, 12), LAT, LON, 1.0);
        assert_eq!(day.dark_start, None);
        assert_eq!(day.dark_end, None);
        assert!(day.deepest > -18.0 && day.deepest < -14.0, "deepest {}", day.deepest);
        assert!(day.marks.iter().any(|m| m.kind == "nautical dusk"));
        assert!(!day.marks.iter().any(|m| m.kind == "astro dusk"));
    }

    #[test]
    fn the_moon_now() {
        // 2026-11-24 14:53 UT is a full moon.
        let now = clock_now(unix(2026, 11, 24, 15), LAT, LON, 0.0);
        assert!(now.moon_lit > 0.98, "lit {}", now.moon_lit);
        assert!((now.moon_age - 15.3).abs() < 0.5, "age {}", now.moon_age);
        assert_eq!(now.moon_name, "Full moon");
        assert_eq!(now.bodies.len(), 7);
        assert!(now.sidereal >= 0.0 && now.sidereal < 24.0);
        assert_eq!(now.bodies[0].con, "Scorpius");
        assert_eq!(moon_name(0.1), "Waxing crescent");
        assert_eq!(moon_name(0.99), "New moon");
    }

    #[test]
    fn the_weeks_ahead() {
        let ev = clock_events(unix(2026, 11, 21, 0), 60);
        assert!(ev.windows(2).all(|w| w[0].at <= w[1].at));
        let full = ev.iter().find(|e| e.text.starts_with("Full Moon")).expect("a full moon");
        assert_eq!(full.kind, "moon");
        assert!((full.at - unix(2026, 11, 24, 15)).abs() < 3600);
        assert!(ev.iter().any(|e| e.kind == "season" && e.text == "December solstice"));
        assert!(ev.iter().any(|e| e.kind == "shower" && e.text.starts_with("Geminids")));
        assert!(ev.iter().all(|e| !e.text.starts_with(|c: char| !c.is_alphanumeric())));
    }

    #[test]
    fn passes_and_peaks_between_samples() {
        let alt = [-2.0, -1.0, 1.0, 3.0, 2.0, -1.0];
        assert_eq!(passes(0, &alt, 0.0), vec![(900, true), (4 * 600 + 400, false)]);
        let (t, h) = peak(0, &alt).unwrap();
        assert!(t > 1800 && t < 2100, "peak at {t}");
        assert!(h >= 3.0);
    }
}
