// outside: three weather forecasts for one spot, side by side.
//
// Yr (MET Norway), Storm (read from TV 2's weather pages) and GFS (NOAA's
// model, through Open-Meteo). The Kotlin shell fetches and caches the three
// raw bodies; this module builds the requests, parses the bodies and lays
// out the days and hours. Pure compute, no I/O, and no panics: the release
// profile aborts on one.

use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;

/// Sources in column order: 0 = Yr, 1 = Storm, 2 = GFS.
const SOURCES: usize = 3;

/// One weather picture, shared by the three sources' own symbol sets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Sky {
    Clear,
    Fair,
    PartlyCloudy,
    Cloudy,
    Fog,
    LightRain,
    Rain,
    HeavyRain,
    Sleet,
    Snow,
    Thunder,
}

/// One step of one source's forecast: an hour, or six hours further out.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Step {
    /// Start of the step, seconds since 1970 (UTC).
    pub epoch: i64,
    /// How long the step lasts: 1 or 6.
    pub hours: u32,
    pub temp: f64,
    /// Highest and lowest temperature within the step.
    pub hi: f64,
    pub lo: f64,
    pub feels: Option<f64>,
    /// Millimetres over the whole step.
    pub rain: f64,
    /// Chance of rain, percent.
    pub chance: Option<f64>,
    /// Metres per second.
    pub wind: f64,
    pub gust: Option<f64>,
    /// Degrees the wind comes from.
    pub wind_dir: i32,
    pub sky: Sky,
    pub night: bool,
}

/// One source's summary of one day.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SourceDay {
    pub temp_max: f64,
    pub temp_min: f64,
    pub rain: f64,
    /// The strongest mean wind of the day.
    pub wind: f64,
    pub gust: Option<f64>,
    pub sky: Sky,
}

/// One line of a day's hour table: what each source says from this hour.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HourRow {
    pub hour: u32,
    pub cells: Vec<Option<Step>>,
}

/// How well the sources agree on a day.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Agreement {
    /// Fewer than two sources reach this day.
    Single,
    Agree,
    Mixed,
    Split,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutsideDay {
    /// "2026-10-05"
    pub date: String,
    /// "Today", "Tomorrow", "Wed 7 Oct"
    pub label: String,
    pub cells: Vec<Option<SourceDay>>,
    pub rows: Vec<HourRow>,
    pub agreement: Agreement,
    /// What they disagree on, "Rain: 0 to 14 mm"; empty when they agree.
    pub disagreement: String,
    /// 0 to 10: how good the day's best three hours are for being outside.
    pub score: Option<u32>,
    /// The best stretch of the day, "13–17" or "all day"; empty when no
    /// stretch is good.
    pub best: String,
    /// "07:54"; empty where the sun does not rise or set that day.
    pub sunrise: String,
    pub sunset: String,
    /// The highest level of a warning that touches the day: 2 yellow,
    /// 3 orange, 4 red; 0 for none.
    pub warning: u32,
}

/// An official weather warning for the spot (MET Norway, so Norway only).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Warning {
    /// 2 yellow, 3 orange, 4 red.
    pub level: u32,
    /// "Rain", "Gale"
    pub event: String,
    /// "Vestland"
    pub area: String,
    /// "Tomorrow 06:00 to Tue 6 Oct 12:00", by the spot's clock.
    pub span: String,
    /// What is expected, and what to do about it.
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Outside {
    /// What each source says right now.
    pub now: Vec<Option<Step>>,
    pub days: Vec<OutsideDay>,
    /// The best of the next seven days for being outside, as an index
    /// into `days`.
    pub best_day: Option<u32>,
    /// The warnings in force or coming, the gravest first.
    pub warnings: Vec<Warning>,
}

/// The spot's clock: seconds east of UTC, and the one change (summer time
/// starting or ending) that falls inside the forecast, if any.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Tz {
    pub offset: i32,
    /// When the offset changes, seconds since 1970; 0 for no change.
    pub change_at: i64,
    pub offset_after: i32,
}

impl Tz {
    fn offset_at(&self, epoch: i64) -> i64 {
        if self.change_at != 0 && epoch >= self.change_at {
            self.offset_after as i64
        } else {
            self.offset as i64
        }
    }
    fn local(&self, epoch: i64) -> i64 {
        epoch + self.offset_at(epoch)
    }
}

/// A place the user can pick: a search hit or a saved one.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Spot {
    pub name: String,
    /// "Vestland, Norway · 1214 m"
    pub region: String,
    pub lat: f64,
    pub lon: f64,
    /// "Europe/Oslo"; empty means the phone's own zone.
    pub tz: String,
}

/// What the shell has to fetch for one spot.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Requests {
    /// GET
    pub yr_url: String,
    /// POST `storm_body` as application/json
    pub storm_url: String,
    pub storm_body: String,
    /// GET
    pub gfs_url: String,
    /// GET; empty where MET Norway's warnings do not reach.
    pub alerts_url: String,
}

// ---------- requests ----------

const STORM_QUERY: &str = "query($placeId: String!, $from: Date!, $to: Date!) { \
forecastByPlaceId(placeId: $placeId, from: $from, to: $to, geoHash: true) { \
days { date weatherOneHourSteps { ...S } weatherSixHourSteps { ...S } } } } \
fragment S on ForecastPeriod { startTime endTime symbol precipitation \
precipitationProbability temperature temperatureFeelslike windSpeed \
windDirection gust }";

/// The requests for a spot. Coordinates go out with four decimals
/// (11 metres), which is what MET asks for and lets its cache answer.
#[uniffi::export]
pub fn outside_requests(lat: f64, lon: f64, now: i64) -> Requests {
    let (la, lo) = (format!("{:.4}", lat), format!("{:.4}", lon));
    let today = now.div_euclid(86400);
    // TV 2 names a point by its geohash: "#" + hash, base64.
    let place = base64::engine::general_purpose::STANDARD.encode(format!("#{}", geohash(lat, lon)));
    Requests {
        yr_url: format!(
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat={}&lon={}",
            la, lo
        ),
        storm_url: "https://www.tv2.no/vaer/backend-api".into(),
        storm_body: json!({
            "query": STORM_QUERY,
            "variables": { "placeId": place, "from": date_text(today), "to": date_text(today + 16) },
        })
        .to_string(),
        gfs_url: format!(
            "https://api.open-meteo.com/v1/forecast?latitude={}&longitude={}\
             &hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,\
             weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day\
             &models=gfs_seamless&forecast_days=16&wind_speed_unit=ms&timeformat=unixtime",
            la, lo
        ),
        // MET warns for Norway, its waters and Svalbard; the box is wide
        // and a point outside any warning area gets an empty answer.
        alerts_url: if (57.0..=82.0).contains(&lat) && (-10.0..=35.0).contains(&lon) {
            format!("https://api.met.no/weatherapi/metalerts/2.0/current.json?lat={}&lon={}&lang=en", la, lo)
        } else {
            String::new()
        },
    }
}

/// The 12-letter geohash of a point.
fn geohash(lat: f64, lon: f64) -> String {
    const LETTERS: &[u8; 32] = b"0123456789bcdefghjkmnpqrstuvwxyz";
    let (mut lat_lo, mut lat_hi) = (-90.0_f64, 90.0_f64);
    let (mut lon_lo, mut lon_hi) = (-180.0_f64, 180.0_f64);
    let mut out = String::with_capacity(12);
    let mut bits = 0_usize;
    for i in 0..60 {
        // Even bits halve the longitude, odd bits the latitude.
        let (v, lo, hi) = if i % 2 == 0 {
            (lon, &mut lon_lo, &mut lon_hi)
        } else {
            (lat, &mut lat_lo, &mut lat_hi)
        };
        let mid = (*lo + *hi) / 2.0;
        bits <<= 1;
        if v >= mid {
            bits |= 1;
            *lo = mid;
        } else {
            *hi = mid;
        }
        if i % 5 == 4 {
            out.push(LETTERS[bits & 31] as char);
            bits = 0;
        }
    }
    out
}

// ---------- time ----------

/// Days since 1970-01-01 for a calendar date.
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// The calendar date (year, month, day) of a day number.
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = z.div_euclid(146097);
    let doe = z.rem_euclid(146097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

fn date_text(day: i64) -> String {
    let (y, m, d) = civil_from_days(day);
    format!("{:04}-{:02}-{:02}", y, m, d)
}

/// Seconds since 1970 for "2026-10-04T22:00:00+02:00" or "...Z".
fn iso_epoch(s: &str) -> Option<i64> {
    let num = |a: usize, z: usize| -> Option<i64> { s.get(a..z)?.parse().ok() };
    let day = days_from_civil(num(0, 4)?, num(5, 7)?, num(8, 10)?);
    let mut t = day * 86400 + num(11, 13)? * 3600 + num(14, 16)? * 60 + num(17, 19)?;
    let zone = s.get(19..)?.trim_start_matches(|c: char| c == '.' || c.is_ascii_digit());
    if zone.starts_with('+') || zone.starts_with('-') {
        let h: i64 = zone.get(1..3)?.parse().ok()?;
        let m: i64 = zone.get(4..6).and_then(|m| m.parse().ok()).unwrap_or(0);
        let off = h * 3600 + m * 60;
        t -= if zone.starts_with('+') { off } else { -off };
    }
    Some(t)
}

/// "07:54" as 7.9; None for anything else.
fn clock_hours(s: &str) -> Option<f64> {
    let (h, m) = s.split_once(':')?;
    let m = m.get(0..2)?;
    Some(h.trim().parse::<f64>().ok()? + m.parse::<f64>().ok()? / 60.0)
}

// ---------- the three parsers ----------

fn num(v: &Value, key: &str) -> Option<f64> {
    v.get(key).and_then(Value::as_f64)
}

fn sky_yr(code: &str) -> (Sky, bool) {
    let (base, tail) = code.split_once('_').unwrap_or((code, ""));
    let sky = if base.contains("thunder") {
        Sky::Thunder
    } else if base.contains("sleet") {
        Sky::Sleet
    } else if base.contains("snow") {
        Sky::Snow
    } else if base.contains("rain") {
        if base.starts_with("light") {
            Sky::LightRain
        } else if base.starts_with("heavy") {
            Sky::HeavyRain
        } else {
            Sky::Rain
        }
    } else {
        match base {
            "clearsky" => Sky::Clear,
            "fair" => Sky::Fair,
            "partlycloudy" => Sky::PartlyCloudy,
            "fog" => Sky::Fog,
            _ => Sky::Cloudy,
        }
    };
    (sky, tail == "night")
}

fn sky_storm(symbol: &str, rain_per_hour: f64) -> (Sky, bool) {
    let sky = if symbol.contains("THUNDER") {
        Sky::Thunder
    } else if symbol.contains("SLEET") {
        Sky::Sleet
    } else if symbol.contains("SNOW") {
        Sky::Snow
    } else if symbol.contains("RAIN") {
        // Storm has no symbol for heavy rain; the amount tells.
        if rain_per_hour >= 3.0 {
            Sky::HeavyRain
        } else if symbol == "LIGHT_RAIN" {
            Sky::LightRain
        } else {
            Sky::Rain
        }
    } else if symbol.starts_with("CLEAR") {
        Sky::Clear
    } else if symbol.starts_with("CLOUDY_LIGHT") {
        Sky::Fair
    } else if symbol.starts_with("PARTLY_CLOUDY") {
        Sky::PartlyCloudy
    } else if symbol == "FOG" {
        Sky::Fog
    } else {
        Sky::Cloudy
    };
    (sky, symbol.ends_with("_NIGHT"))
}

/// The WMO weather codes Open-Meteo hands out.
fn sky_wmo(code: i64) -> Sky {
    match code {
        0 => Sky::Clear,
        1 => Sky::Fair,
        2 => Sky::PartlyCloudy,
        45 | 48 => Sky::Fog,
        51 | 53 | 55 | 61 | 80 => Sky::LightRain,
        63 | 81 => Sky::Rain,
        65 | 82 => Sky::HeavyRain,
        56 | 57 | 66 | 67 => Sky::Sleet,
        71 | 73 | 75 | 77 | 85 | 86 => Sky::Snow,
        95 | 96 | 99 => Sky::Thunder,
        _ => Sky::Cloudy,
    }
}

/// MET Norway's locationforecast/2.0/complete: hourly for about two days,
/// then six-hour steps.
fn parse_yr(body: &str) -> Vec<Step> {
    let body: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let Some(series) = body.pointer("/properties/timeseries").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut steps = Vec::new();
    for ts in series {
        let Some(epoch) = ts.get("time").and_then(Value::as_str).and_then(iso_epoch) else { continue };
        let Some(at) = ts.pointer("/data/instant/details") else { continue };
        let Some(temp) = num(at, "air_temperature") else { continue };
        let (hours, next) = match (ts.pointer("/data/next_1_hours"), ts.pointer("/data/next_6_hours")) {
            (Some(n), _) => (1, n),
            (None, Some(n)) => (6, n),
            _ => continue,
        };
        let details = next.get("details");
        let pick = |key: &str| details.and_then(|d| num(d, key));
        let code = next.pointer("/summary/symbol_code").and_then(Value::as_str).unwrap_or("");
        let (sky, night) = sky_yr(code);
        steps.push(Step {
            epoch,
            hours,
            temp,
            hi: pick("air_temperature_max").unwrap_or(temp).max(temp),
            lo: pick("air_temperature_min").unwrap_or(temp).min(temp),
            feels: num(at, "apparent_air_temperature"),
            rain: pick("precipitation_amount").unwrap_or(0.0),
            chance: pick("probability_of_precipitation"),
            wind: num(at, "wind_speed").unwrap_or(0.0),
            gust: num(at, "wind_speed_of_gust"),
            wind_dir: num(at, "wind_from_direction").unwrap_or(0.0) as i32,
            sky,
            night,
        });
    }
    steps
}

/// TV 2's forecastByPlaceId: hourly for about eleven days, then six-hour
/// steps to day fifteen.
fn parse_storm(body: &str) -> Vec<Step> {
    let body: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let Some(days) = body.pointer("/data/forecastByPlaceId/days").and_then(Value::as_array) else {
        return Vec::new();
    };
    let list = |day: &Value, key: &str| -> Vec<Value> {
        day.get(key).and_then(Value::as_array).cloned().unwrap_or_default()
    };
    let mut steps = Vec::new();
    for day in days {
        let mut periods = list(day, "weatherOneHourSteps");
        if periods.is_empty() {
            periods = list(day, "weatherSixHourSteps");
        }
        for p in &periods {
            let text = |key: &str| p.get(key).and_then(Value::as_str);
            let (Some(start), Some(end)) = (
                text("startTime").and_then(iso_epoch),
                text("endTime").and_then(iso_epoch),
            ) else {
                continue;
            };
            let Some(temp) = num(p, "temperature") else { continue };
            let hours = ((end - start) / 3600).clamp(1, 24) as u32;
            let rain = num(p, "precipitation").unwrap_or(0.0);
            let (sky, night) = sky_storm(text("symbol").unwrap_or(""), rain / hours as f64);
            steps.push(Step {
                epoch: start,
                hours,
                temp,
                hi: temp,
                lo: temp,
                feels: num(p, "temperatureFeelslike"),
                rain,
                chance: num(p, "precipitationProbability"),
                wind: num(p, "windSpeed").unwrap_or(0.0),
                gust: num(p, "gust"),
                wind_dir: num(p, "windDirection").unwrap_or(0.0) as i32,
                sky,
                night,
            });
        }
    }
    steps
}

/// Open-Meteo's hourly arrays for the GFS model. Rain, chance and gust are
/// given for the hour that ended, so the step from hour i reads them at i+1.
fn parse_gfs(body: &str) -> Vec<Step> {
    let body: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let Some(hourly) = body.get("hourly") else { return Vec::new() };
    let at = |key: &str, i: usize| -> Option<f64> {
        hourly.get(key)?.as_array()?.get(i)?.as_f64()
    };
    let count = hourly.get("time").and_then(Value::as_array).map_or(0, Vec::len);
    let mut steps = Vec::new();
    for i in 0..count.saturating_sub(1) {
        let (Some(epoch), Some(temp)) = (at("time", i), at("temperature_2m", i)) else { continue };
        steps.push(Step {
            epoch: epoch as i64,
            hours: 1,
            temp,
            hi: temp,
            lo: temp,
            feels: at("apparent_temperature", i),
            rain: at("precipitation", i + 1).unwrap_or(0.0),
            chance: at("precipitation_probability", i + 1),
            wind: at("wind_speed_10m", i).unwrap_or(0.0),
            gust: at("wind_gusts_10m", i + 1),
            wind_dir: at("wind_direction_10m", i).unwrap_or(0.0) as i32,
            sky: sky_wmo(at("weather_code", i).unwrap_or(3.0) as i64),
            night: at("is_day", i) == Some(0.0),
        });
    }
    steps
}

/// Whether a fetched body holds a forecast. The shell keeps only bodies
/// that do, so an error page never replaces a good forecast in its cache.
#[uniffi::export]
pub fn outside_usable(source: u32, body: String) -> bool {
    let steps = match source {
        0 => parse_yr(&body),
        1 => parse_storm(&body),
        2 => parse_gfs(&body),
        // The warnings: an empty list is a good answer too.
        _ => {
            return serde_json::from_str::<Value>(&body)
                .map_or(false, |v| v.get("features").map_or(false, Value::is_array))
        }
    };
    !steps.is_empty()
}

/// MET Norway's MetAlerts: the warnings still to end, each with the time
/// it starts and ends.
fn parse_alerts(body: &str, tz: &Tz, now: i64) -> Vec<(Warning, i64, i64)> {
    let body: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let Some(features) = body.get("features").and_then(Value::as_array) else {
        return Vec::new();
    };
    let today = tz.local(now).div_euclid(86400);
    let mut out = Vec::new();
    for f in features {
        let Some(props) = f.get("properties") else { continue };
        let text = |key: &str| props.get(key).and_then(Value::as_str).unwrap_or("").trim();
        // Drills and tests are sent on the same feed.
        if !matches!(text("status"), "" | "Actual") {
            continue;
        }
        let time = |i: usize| {
            f.pointer("/when/interval")?.as_array()?.get(i)?.as_str().and_then(iso_epoch)
        };
        let (Some(from), Some(to)) = (time(0), time(1)) else { continue };
        if to <= now {
            continue;
        }
        // "2; yellow; Moderate"
        let level = text("awareness_level")
            .split(';')
            .next()
            .and_then(|n| n.trim().parse::<u32>().ok())
            .unwrap_or(2)
            .clamp(2, 4);
        let event = match text("eventAwarenessName") {
            "" => text("event"),
            name => name,
        };
        let stamp = |epoch: i64| {
            let local = tz.local(epoch);
            let minutes = local.rem_euclid(86400) / 60;
            (local.div_euclid(86400), format!("{:02}:{:02}", minutes / 60, minutes % 60))
        };
        let ((day_a, clock_a), (day_z, clock_z)) = (stamp(from), stamp(to));
        let span = if day_a == day_z {
            format!("{} {} to {}", day_label(day_a, today), clock_a, clock_z)
        } else {
            format!("{} {} to {} {}", day_label(day_a, today), clock_a, day_label(day_z, today), clock_z)
        };
        let words = [text("description"), text("instruction")];
        out.push((
            Warning {
                level,
                event: event.to_string(),
                area: text("area").to_string(),
                span,
                text: words.iter().filter(|w| !w.is_empty()).cloned().collect::<Vec<_>>().join(" "),
            },
            from,
            to,
        ));
    }
    // The gravest first, then the one that starts first.
    out.sort_by_key(|(w, from, _)| (std::cmp::Reverse(w.level), *from));
    out
}

/// "Today", "Tomorrow", "Wed 7 Oct"
fn day_label(day: i64, today: i64) -> String {
    match day - today {
        0 => "Today".into(),
        1 => "Tomorrow".into(),
        _ => {
            let (_, m, d) = civil_from_days(day);
            let weekday = WEEKDAYS[(day + 4).rem_euclid(7) as usize];
            format!("{} {} {}", weekday, d, MONTHS[(m - 1).clamp(0, 11) as usize])
        }
    }
}

/// In time order, none overlapping, none that ended before `now`.
fn tidy(mut steps: Vec<Step>, now: i64) -> Vec<Step> {
    steps.sort_by_key(|s| s.epoch);
    let mut free_from = i64::MIN;
    steps.retain(|s| {
        let end = s.epoch + s.hours as i64 * 3600;
        if s.epoch < free_from || end <= now {
            return false;
        }
        free_from = end;
        true
    });
    steps
}

// ---------- the layout ----------

const WEEKDAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS: [&str; 12] =
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

fn is_wet(sky: Sky) -> bool {
    matches!(sky, Sky::LightRain | Sky::Rain | Sky::HeavyRain | Sky::Sleet | Sky::Snow | Sky::Thunder)
}

/// How much sky is covered, 0 (clear) to 3 (overcast).
fn cloud_rank(sky: Sky) -> f64 {
    match sky {
        Sky::Clear => 0.0,
        Sky::Fair => 1.0,
        Sky::PartlyCloudy => 2.0,
        _ => 3.0,
    }
}

/// One symbol for a whole day, from the steps that start in its waking
/// hours (`from`..`to`, local clock).
fn day_sky(steps: &[(u32, &Step)], from: f64, to: f64) -> Sky {
    let awake: Vec<&Step> = steps
        .iter()
        .filter(|(hour, _)| (*hour as f64) >= from.floor() && (*hour as f64) < to)
        .map(|(_, s)| *s)
        .collect();
    let seen: Vec<&Step> = if awake.is_empty() { steps.iter().map(|(_, s)| *s).collect() } else { awake };
    if seen.iter().any(|s| s.sky == Sky::Thunder) {
        return Sky::Thunder;
    }
    let rain: f64 = seen.iter().map(|s| s.rain).sum();
    if rain >= 0.5 {
        let hours_of = |sky: Sky| -> u32 { seen.iter().filter(|s| s.sky == sky).map(|s| s.hours).sum() };
        let wet: u32 = seen.iter().filter(|s| is_wet(s.sky)).map(|s| s.hours).sum();
        if hours_of(Sky::Snow) * 2 > wet {
            return Sky::Snow;
        }
        if (hours_of(Sky::Sleet) + hours_of(Sky::Snow)) * 2 > wet {
            return Sky::Sleet;
        }
        return if rain < 3.0 {
            Sky::LightRain
        } else if rain < 12.0 {
            Sky::Rain
        } else {
            Sky::HeavyRain
        };
    }
    let total: u32 = seen.iter().map(|s| s.hours).sum();
    if total == 0 {
        return Sky::Cloudy;
    }
    let fog: u32 = seen.iter().filter(|s| s.sky == Sky::Fog).map(|s| s.hours).sum();
    if fog * 2 > total {
        return Sky::Fog;
    }
    let cover: f64 = seen.iter().map(|s| cloud_rank(s.sky) * s.hours as f64).sum::<f64>() / total as f64;
    match cover.round() as i64 {
        0 => Sky::Clear,
        1 => Sky::Fair,
        2 => Sky::PartlyCloudy,
        _ => Sky::Cloudy,
    }
}

fn source_day(steps: &[(u32, &Step)], first_day: bool, from: f64, to: f64) -> Option<SourceDay> {
    let covered: u32 = steps.iter().map(|(_, s)| s.hours).sum();
    // A source's last day is often a few night hours; a top temperature
    // read from those would mislead.
    if covered == 0 || (covered < 12 && !first_day) {
        return None;
    }
    let all = || steps.iter().map(|(_, s)| *s);
    Some(SourceDay {
        temp_max: all().map(|s| s.hi).fold(f64::NEG_INFINITY, f64::max),
        temp_min: all().map(|s| s.lo).fold(f64::INFINITY, f64::min),
        rain: all().map(|s| s.rain).sum(),
        wind: all().map(|s| s.wind).fold(0.0, f64::max),
        gust: all().filter_map(|s| s.gust).fold(None, |a: Option<f64>, g| Some(a.map_or(g, |a| a.max(g)))),
        sky: day_sky(steps, from, to),
    })
}

/// Compare the sources' day summaries. Points for each thing they differ
/// on; the biggest difference is named.
fn agreement(cells: &[Option<SourceDay>]) -> (Agreement, String) {
    let have: Vec<&SourceDay> = cells.iter().flatten().collect();
    if have.len() < 2 {
        return (Agreement::Single, String::new());
    }
    let span = |f: fn(&SourceDay) -> f64| -> (f64, f64) {
        have.iter().fold((f64::INFINITY, f64::NEG_INFINITY), |(lo, hi), d| (lo.min(f(d)), hi.max(f(d))))
    };
    let (t_lo, t_hi) = span(|d| d.temp_max);
    let (r_lo, r_hi) = span(|d| d.rain);
    let (w_lo, w_hi) = span(|d| d.wind);

    let temp = if t_hi - t_lo >= 7.0 { 2 } else if t_hi - t_lo >= 4.0 { 1 } else { 0 };
    let rain = if r_lo < 0.5 && r_hi >= 5.0 {
        2
    } else if r_hi >= 1.0 && r_hi - r_lo >= (0.6 * r_hi).max(2.0) {
        1
    } else {
        0
    };
    // A light breeze against a calm is no disagreement worth a mark.
    let wind = if w_hi >= 8.0 && w_hi - w_lo >= 4.0 { 1 } else { 0 };

    let level = match temp + rain + wind {
        0 => Agreement::Agree,
        1 | 2 => Agreement::Mixed,
        _ => Agreement::Split,
    };
    let snowy = have.iter().any(|d| matches!(d.sky, Sky::Snow | Sky::Sleet));
    let note = if rain > 0 && rain >= temp {
        format!("{}: {} to {} mm", if snowy { "Rain or snow" } else { "Rain" }, mm(r_lo), mm(r_hi))
    } else if temp > 0 {
        format!("Top temperature: {}° to {}°", t_lo.round() as i64, t_hi.round() as i64)
    } else if wind > 0 {
        format!("Wind: {} to {} m/s", w_lo.round() as i64, w_hi.round() as i64)
    } else {
        String::new()
    };
    (level, note)
}

/// Millimetres the way a forecast prints them: "0", "0.4", "14".
fn mm(v: f64) -> String {
    if v < 0.05 {
        "0".into()
    } else if v < 10.0 {
        format!("{:.1}", v)
    } else {
        format!("{:.0}", v)
    }
}

/// The sources' values for one hour, summed; `n` sources reached it.
#[derive(Clone, Copy, Default)]
struct HourSum {
    n: f64,
    rain: f64,
    wind: f64,
    temp: f64,
    cloud: f64,
}

/// 0 to 10: how pleasant an hour is to be outside in. Rain costs most,
/// then wind, cold or heat, and a grey sky.
fn hour_score(h: &HourSum) -> f64 {
    let (rain, wind, temp, cloud) = (h.rain / h.n, h.wind / h.n, h.temp / h.n, h.cloud / h.n);
    let mut score = 10.0;
    if rain >= 0.1 {
        score -= (2.0 + rain * 2.0).min(6.0);
    }
    if wind > 5.0 {
        score -= ((wind - 5.0) * 0.7).min(4.0);
    }
    if temp < 12.0 {
        score -= ((12.0 - temp) * 0.25).min(4.0);
    } else if temp > 26.0 {
        score -= ((temp - 26.0) * 0.4).min(3.0);
    }
    score -= cloud * 0.5;
    score.clamp(0.0, 10.0)
}

/// The best three hours in a row among the scored hours, widened while the
/// hours next to it are about as good. Returns (score, first hour, hour
/// after the last).
fn best_stretch(scores: &[Option<f64>; 24]) -> Option<(f64, usize, usize)> {
    let mut best: Option<(f64, usize, usize)> = None;
    let mut start = 0;
    while start < 24 {
        if scores[start].is_none() {
            start += 1;
            continue;
        }
        let mut end = start;
        while end < 24 && scores[end].is_some() {
            end += 1;
        }
        // scores[start..end] is one unbroken run.
        let width = (end - start).min(3);
        for a in start..=(end - width) {
            let mean = scores[a..a + width].iter().flatten().sum::<f64>() / width as f64;
            if best.map_or(true, |(b, _, _)| mean > b + 1e-9) {
                best = Some((mean, a, a + width));
            }
        }
        start = end;
    }
    let (mean, mut a, mut z) = best?;
    let near = |i: usize| scores[i].is_some_and(|s| s >= mean - 1.0 && s >= 6.0);
    while a > 0 && near(a - 1) {
        a -= 1;
    }
    while z < 24 && near(z) {
        z += 1;
    }
    Some((mean, a, z))
}

/// Build the whole screen from the cached bodies. A forecast that is
/// missing or will not parse leaves its column empty.
#[uniffi::export]
pub fn outside_build(
    yr: Option<String>,
    storm: Option<String>,
    gfs: Option<String>,
    alerts: Option<String>,
    lat: f64,
    lon: f64,
    tz: Tz,
    now: i64,
) -> Outside {
    let alerts = alerts.as_deref().map(|a| parse_alerts(a, &tz, now)).unwrap_or_default();
    let sources: [Vec<Step>; SOURCES] = [
        tidy(yr.as_deref().map(parse_yr).unwrap_or_default(), now),
        tidy(storm.as_deref().map(parse_storm).unwrap_or_default(), now),
        tidy(gfs.as_deref().map(parse_gfs).unwrap_or_default(), now),
    ];

    // Each step lands on the local day it starts in; each hour it covers
    // adds to that hour's sum for the scoring.
    let mut by_day: BTreeMap<i64, [Vec<(u32, &Step)>; SOURCES]> = BTreeMap::new();
    let mut sums: BTreeMap<i64, [HourSum; 24]> = BTreeMap::new();
    for (i, steps) in sources.iter().enumerate() {
        for s in steps {
            let local = tz.local(s.epoch);
            let hour = (local.rem_euclid(86400) / 3600) as u32;
            by_day.entry(local.div_euclid(86400)).or_default()[i].push((hour, s));
            for k in 0..s.hours as i64 {
                let at = s.epoch + k * 3600;
                if at + 3600 <= now {
                    continue;
                }
                let local = tz.local(at);
                let slot = &mut sums.entry(local.div_euclid(86400)).or_insert([HourSum::default(); 24])
                    [(local.rem_euclid(86400) / 3600) as usize];
                slot.n += 1.0;
                slot.rain += s.rain / s.hours as f64;
                slot.wind += s.wind;
                slot.temp += s.feels.unwrap_or(s.temp);
                slot.cloud += cloud_rank(s.sky);
            }
        }
    }

    let today = tz.local(now).div_euclid(86400);
    let mut days = Vec::new();
    for (&day, per_source) in &by_day {
        let (y, m, d) = civil_from_days(day);

        // Waking daylight: sunrise to sunset, kept inside 06 to 22.
        let noon = day * 86400 + 43200;
        let zone_hours = tz.offset_at(noon - tz.offset as i64) as f64 / 3600.0;
        let sun = orbit::sun_times(y as i32, m as u32, d as u32, lat, lon, zone_hours);
        let (sunrise, sunset) = sun.clone().unwrap_or_default();
        let (from, to) = match sun.and_then(|(r, s)| Some((clock_hours(&r)?, clock_hours(&s)?))) {
            Some((rise, set)) if set > rise => (rise.max(6.0), set.min(22.0)),
            // No rise or set: the sun is up all day in the summer half of
            // the year, and gone in the winter half.
            _ if (4..=9).contains(&m) == (lat >= 0.0) => (6.0, 22.0),
            _ => (10.0, 14.0),
        };

        let cells: Vec<Option<SourceDay>> =
            per_source.iter().map(|steps| source_day(steps, day == today, from, to)).collect();
        if cells.iter().all(Option::is_none) {
            continue;
        }

        let mut rows: BTreeMap<u32, Vec<Option<Step>>> = BTreeMap::new();
        for (i, steps) in per_source.iter().enumerate() {
            for (hour, s) in steps {
                rows.entry(*hour).or_insert_with(|| vec![None; SOURCES])[i] = Some((*s).clone());
            }
        }

        let mut scores = [None; 24];
        if let Some(day_sums) = sums.get(&day) {
            for (hour, sum) in day_sums.iter().enumerate() {
                if sum.n > 0.0 && (hour as f64) >= from.floor() && (hour as f64) < to {
                    scores[hour] = Some(hour_score(sum));
                }
            }
        }
        let stretch = best_stretch(&scores);
        let waking = scores.iter().filter(|s| s.is_some()).count();
        let best = match stretch {
            Some((mean, a, z)) if mean >= 6.0 => {
                if z - a == waking && waking >= 8 {
                    "all day".to_string()
                } else {
                    format!("{:02}–{:02}", a, z)
                }
            }
            _ => String::new(),
        };

        let (level, disagreement) = agreement(&cells);
        // A warning touches the day when any of its time falls inside it.
        let warning = alerts
            .iter()
            .filter(|(_, from, to)| {
                tz.local(*from).div_euclid(86400) <= day && day <= tz.local(*to - 1).div_euclid(86400)
            })
            .map(|(w, _, _)| w.level)
            .max()
            .unwrap_or(0);
        days.push(OutsideDay {
            date: date_text(day),
            label: day_label(day, today),
            cells,
            rows: rows.into_iter().map(|(hour, cells)| HourRow { hour, cells }).collect(),
            agreement: level,
            disagreement,
            score: stretch.map(|(mean, _, _)| mean.round() as u32),
            best,
            sunrise,
            sunset,
            warning,
        });
    }

    // The best of the next seven days, the earliest on a tie.
    let mut best_day: Option<(u32, usize)> = None;
    for (i, day) in days.iter().enumerate().take(7) {
        if let Some(score) = day.score {
            if score >= 6 && best_day.map_or(true, |(b, _)| score > b) {
                best_day = Some((score, i));
            }
        }
    }

    Outside {
        now: sources
            .iter()
            .map(|steps| steps.first().filter(|s| s.epoch <= now + 3600).cloned())
            .collect(),
        days,
        best_day: best_day.map(|(_, i)| i as u32),
        warnings: alerts.into_iter().map(|(w, _, _)| w).collect(),
    }
}

// ---------- places ----------

/// The search request for a place name (Open-Meteo's geocoder).
#[uniffi::export]
pub fn outside_search_url(query: String) -> String {
    let mut name = String::new();
    for b in query.trim().bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            name.push(b as char);
        } else {
            name.push_str(&format!("%{:02X}", b));
        }
    }
    format!("https://geocoding-api.open-meteo.com/v1/search?name={}&count=12&language=en&format=json", name)
}

/// The hits of a search.
#[uniffi::export]
pub fn outside_search_hits(body: String) -> Vec<Spot> {
    let body: Value = match serde_json::from_str(&body) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let Some(results) = body.get("results").and_then(Value::as_array) else { return Vec::new() };
    let mut hits = Vec::new();
    for r in results {
        let text = |key: &str| r.get(key).and_then(Value::as_str).unwrap_or("");
        let (Some(lat), Some(lon)) = (num(r, "latitude"), num(r, "longitude")) else { continue };
        let mut region: Vec<String> = [text("admin1"), text("country")]
            .iter()
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .collect();
        region.dedup();
        let mut region = region.join(", ");
        // 9999 is the geocoder's mark for "height unknown".
        if let Some(height) = num(r, "elevation").filter(|h| *h >= 300.0 && *h < 9000.0) {
            region.push_str(&format!(" · {:.0} m", height));
        }
        hits.push(Spot { name: text("name").to_string(), region, lat, lon, tz: text("timezone").to_string() });
    }
    hits
}

/// The saved places as the text the shell keeps on disk.
#[uniffi::export]
pub fn outside_spots_text(spots: Vec<Spot>) -> String {
    serde_json::to_string(&spots).unwrap_or_else(|_| "[]".into())
}

/// The saved places back from that text; none when it will not parse.
#[uniffi::export]
pub fn outside_spots_parse(text: String) -> Vec<Spot> {
    serde_json::from_str(&text).unwrap_or_default()
}

// ---------- the sky, for the home-screen widget ----------

/// What the widget shows of the sky, for one place at one hour.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Almanac {
    /// The sign the sun is in, "LIBRA".
    pub sign: String,
    /// "07:41 - 18:28"; empty on a day with no sunrise or sunset.
    pub sun: String,
    /// The same for the moon.
    pub moon: String,
    /// How much of the moon's face is lit, 0 to 100.
    pub lit: u32,
    /// "Waning Crescent"
    pub phase: String,
    /// Sunrise and sunset as clock hours, 7.7 for 07:42; -1 when there
    /// is none.
    pub sunrise: f64,
    pub sunset: f64,
}

const SIGNS: [&str; 12] = [
    "ARIES", "TAURUS", "GEMINI", "CANCER", "LEO", "VIRGO",
    "LIBRA", "SCORPIO", "SAGITTARIUS", "CAPRICORN", "AQUARIUS", "PISCES",
];

/// How far along the sun's yearly path a body is, in degrees from where
/// the sun stands at the March equinox.
fn along_sun_path(ra: f64, dec: f64) -> f64 {
    // The tilt of the Earth's axis.
    let tilt = 23.436_f64.to_radians();
    let (ra, dec) = (ra.to_radians(), dec.to_radians());
    (ra.sin() * tilt.cos() + dec.tan() * tilt.sin()).atan2(ra.cos()).to_degrees().rem_euclid(360.0)
}

/// The sky for the widget. `hour` is the local clock, 23.5 for 23:30, and
/// `tz_hours` is that clock's distance east of UTC.
///
/// The lit part comes from the angle between sun and moon at that hour.
/// `orbit::moon_phase` counts days of an average month instead, and is a
/// day off near new moon.
#[uniffi::export]
pub fn outside_sky(year: i32, month: u32, day: u32, hour: f64, lat: f64, lon: f64, tz_hours: f64) -> Almanac {
    let (_, bodies) = orbit::sky_at(year, month, day, hour, lat, lon, tz_hours);
    let at = |name: &str| bodies.iter().find(|b| b.0 == name).map(|b| (b.1, b.2)).unwrap_or((0.0, 0.0));
    let (sun_ra, sun_dec) = at("sun");
    let (moon_ra, moon_dec) = at("moon");

    let sun_at = along_sun_path(sun_ra, sun_dec);
    // How far the moon is ahead of the sun: 0 at new moon, 180 at full.
    let ahead = (along_sun_path(moon_ra, moon_dec) - sun_at).rem_euclid(360.0);
    // The moon gains 12 degrees a day, so 6 to each side is the day itself.
    let phase = match ahead {
        a if !(6.0..354.0).contains(&a) => "New Moon",
        a if a < 84.0 => "Waxing Crescent",
        a if a < 96.0 => "First Quarter",
        a if a < 174.0 => "Waxing Gibbous",
        a if a < 186.0 => "Full Moon",
        a if a < 264.0 => "Waning Gibbous",
        a if a < 276.0 => "Last Quarter",
        _ => "Waning Crescent",
    };
    let (d1, d2) = (sun_dec.to_radians(), moon_dec.to_radians());
    let apart = d1.sin() * d2.sin() + d1.cos() * d2.cos() * (sun_ra - moon_ra).to_radians().cos();

    let line = |t: &Option<(String, String)>| t.as_ref().map(|(r, s)| format!("{r} - {s}")).unwrap_or_default();
    let sun = orbit::sun_times(year, month, day, lat, lon, tz_hours);
    let (sunrise, sunset) = sun
        .as_ref()
        .and_then(|(r, s)| Some((clock_hours(r)?, clock_hours(s)?)))
        .unwrap_or((-1.0, -1.0));
    Almanac {
        sign: SIGNS[(sun_at / 30.0) as usize % 12].to_string(),
        sun: line(&sun),
        moon: line(&orbit::moon_times(year, month, day, lat, lon, tz_hours)),
        lit: ((1.0 - apart) * 50.0).round() as u32,
        phase: phase.to_string(),
        sunrise,
        sunset,
    }
}

/// The smallest amount of rain the app prints for a step: it reads
/// "0.1 mm". `millimetres` in the app's Screen.kt has the same number.
const PRINTED_MM: f64 = 0.05;

/// What the ring of the widget's dial shows for one hour.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DialHour {
    /// No forecast reaches the hour.
    Unknown,
    /// Clear, fair or partly cloudy, by day and by night.
    Sun,
    Cloud,
    /// Rain, sleet or snow.
    Rain,
}

/// The weather of the twelve hours from `now`, for the ring of the
/// widget's dial. Entry 0 is the stretch from the tick at 12 to the tick
/// at 1, entry 3 the one from 3 to 4. A stretch stands for the hour that
/// starts at its first tick, and the hour `now` is in is one of the twelve.
///
/// An hour is rain when the app shows rain for it in any column: a wet
/// symbol, or an amount it prints. So the ring is never dry for an hour
/// that has rain in the app. A dry hour is cloud when the sources together
/// say more than "partly cloudy".
#[uniffi::export]
pub fn outside_dial(
    yr: Option<String>,
    storm: Option<String>,
    gfs: Option<String>,
    tz: Tz,
    now: i64,
) -> Vec<DialHour> {
    let sources = [
        tidy(yr.as_deref().map(parse_yr).unwrap_or_default(), now),
        tidy(storm.as_deref().map(parse_storm).unwrap_or_default(), now),
        tidy(gfs.as_deref().map(parse_gfs).unwrap_or_default(), now),
    ];
    // The hour starts on the local clock, which is not on the UTC hour
    // everywhere.
    let first = now - tz.local(now).rem_euclid(3600);
    let mut dial = vec![DialHour::Unknown; 12];
    for k in 0..12 {
        let start = first + k * 3600;
        // The hour going on is judged from now: the forecast may not
        // reach back to its start.
        let at = start.max(now);
        let (mut n, mut rain, mut cloud) = (0.0, false, 0.0);
        for steps in &sources {
            if let Some(s) = steps.iter().find(|s| s.epoch <= at && at < s.epoch + s.hours as i64 * 3600) {
                n += 1.0;
                rain |= is_wet(s.sky) || s.rain >= PRINTED_MM;
                cloud += cloud_rank(s.sky);
            }
        }
        if n == 0.0 {
            continue;
        }
        dial[(tz.local(start).rem_euclid(43200) / 3600) as usize] = if rain {
            DialHour::Rain
        } else if cloud / n > 2.0 {
            DialHour::Cloud
        } else {
            DialHour::Sun
        };
    }
    dial
}

#[cfg(test)]
mod tests {
    use super::*;

    const OSLO: Tz = Tz { offset: 7200, change_at: 0, offset_after: 7200 };

    fn yr_body() -> String {
        // 2026-10-05: hourly at 10 and 11 UTC, then one six-hour step.
        r#"{"properties":{"timeseries":[
          {"time":"2026-10-05T10:00:00Z","data":{
            "instant":{"details":{"air_temperature":12.0,"apparent_air_temperature":10.5,
              "wind_speed":4.0,"wind_speed_of_gust":8.0,"wind_from_direction":200.0}},
            "next_1_hours":{"summary":{"symbol_code":"lightrain"},
              "details":{"precipitation_amount":0.4,"probability_of_precipitation":70.0}},
            "next_6_hours":{"summary":{"symbol_code":"rain"},"details":{"precipitation_amount":5.0}}}},
          {"time":"2026-10-05T11:00:00Z","data":{
            "instant":{"details":{"air_temperature":13.0,"wind_speed":5.0,"wind_from_direction":210.0}},
            "next_1_hours":{"summary":{"symbol_code":"partlycloudy_day"},
              "details":{"precipitation_amount":0.0}}}},
          {"time":"2026-10-05T12:00:00Z","data":{
            "instant":{"details":{"air_temperature":14.0,"wind_speed":6.0,"wind_from_direction":220.0}},
            "next_6_hours":{"summary":{"symbol_code":"clearsky_night"},
              "details":{"precipitation_amount":1.2,"air_temperature_max":15.5,"air_temperature_min":9.0}}}},
          {"time":"2026-10-05T13:00:00Z","data":{
            "instant":{"details":{"air_temperature":14.5,"wind_speed":6.0}},
            "next_6_hours":{"summary":{"symbol_code":"cloudy"},"details":{"precipitation_amount":1.0}}}},
          {"time":"2026-10-05T18:00:00Z","data":{
            "instant":{"details":{"air_temperature":9.0,"wind_speed":2.0}}}}
        ]}}"#
            .to_string()
    }

    #[test]
    fn dates_go_both_ways() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        let day = days_from_civil(2026, 10, 5);
        assert_eq!(civil_from_days(day), (2026, 10, 5));
        assert_eq!(WEEKDAYS[(day + 4).rem_euclid(7) as usize], "Mon");
        assert_eq!(civil_from_days(days_from_civil(2024, 2, 29)), (2024, 2, 29));
        assert_eq!(date_text(days_from_civil(2027, 1, 1) - 1), "2026-12-31");
    }

    #[test]
    fn timestamps_with_and_without_an_offset() {
        let z = iso_epoch("2026-10-04T20:00:00Z").unwrap();
        assert_eq!(iso_epoch("2026-10-04T22:00:00+02:00"), Some(z));
        assert_eq!(iso_epoch("2026-10-04T14:30:00-05:30"), Some(z));
        assert_eq!(iso_epoch("2026-10-04T20:00:00.000Z"), Some(z));
        assert_eq!(iso_epoch("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(iso_epoch("garbage"), None);
        assert_eq!(iso_epoch(""), None);
    }

    #[test]
    fn geohash_matches_the_ids_tv2_hands_out() {
        // The ids TV 2 gave Bergen and Oslo hold "u4ez94hyttzu" and
        // "u4xsudc0fjej". The positions it printed beside them are rounded,
        // so the first eight letters (19 metres) are what must match.
        assert_eq!(&geohash(60.392994, 5.3241525)[..8], "u4ez94hy");
        assert_eq!(&geohash(59.91272639289, 10.746092369721)[..8], "u4xsudc0");
        assert_eq!(geohash(0.0, 0.0), "s00000000000");
        let r = outside_requests(59.91272639289, 10.746092369721, 1_791_072_000);
        // base64 of "#u4xsudc0": the id starts with it.
        assert!(r.storm_body.contains("\"placeId\":\"I3U0eHN1ZGMw"), "{}", r.storm_body);
        assert!(r.storm_body.contains("\"from\":\"2026-10-04\"") && r.storm_body.contains("\"to\":\"2026-10-20\""));
        assert!(r.yr_url.ends_with("lat=59.9127&lon=10.7461"));
    }

    #[test]
    fn yr_steps_hourly_then_six_hours_without_overlap() {
        let steps = tidy(parse_yr(&yr_body()), 0);
        // 10:00 and 11:00 hourly, 12:00 for six hours; 13:00 would overlap
        // it and 18:00 has nothing ahead of it.
        assert_eq!(steps.iter().map(|s| s.hours).collect::<Vec<_>>(), vec![1, 1, 6]);
        assert_eq!(steps[0].rain, 0.4);
        assert_eq!(steps[0].chance, Some(70.0));
        assert_eq!(steps[0].feels, Some(10.5));
        assert_eq!(steps[0].sky, Sky::LightRain);
        assert_eq!(steps[1].sky, Sky::PartlyCloudy);
        assert!(!steps[1].night);
        assert_eq!((steps[2].hi, steps[2].lo), (15.5, 9.0));
        assert!(steps[2].night);
        assert_eq!(steps[2].sky, Sky::Clear);
    }

    #[test]
    fn storm_prefers_the_hourly_steps_of_a_day() {
        let body = r#"{"data":{"forecastByPlaceId":{"days":[
          {"date":"2026-10-05",
           "weatherOneHourSteps":[
             {"startTime":"2026-10-05T12:00:00+02:00","endTime":"2026-10-05T13:00:00+02:00",
              "symbol":"RAIN","precipitation":3.5,"temperature":11.0,"temperatureFeelslike":9.0,
              "windSpeed":4.0,"windDirection":180,"gust":9.0}],
           "weatherSixHourSteps":[
             {"startTime":"2026-10-05T12:00:00+02:00","endTime":"2026-10-05T18:00:00+02:00",
              "symbol":"RAIN","precipitation":9.0,"temperature":11.0}]},
          {"date":"2026-10-06","weatherOneHourSteps":[],
           "weatherSixHourSteps":[
             {"startTime":"2026-10-06T00:00:00+02:00","endTime":"2026-10-06T06:00:00+02:00",
              "symbol":"CLOUDY_LIGHT_NIGHT","precipitation":0.0,"temperature":7.0,"windSpeed":2.0}]}
        ]}}}"#;
        let steps = parse_storm(body);
        assert_eq!(steps.len(), 2);
        assert_eq!((steps[0].hours, steps[0].sky), (1, Sky::HeavyRain));
        assert_eq!(steps[0].epoch, iso_epoch("2026-10-05T10:00:00Z").unwrap());
        assert_eq!((steps[1].hours, steps[1].sky, steps[1].night), (6, Sky::Fair, true));
    }

    #[test]
    fn gfs_reads_rain_from_the_hour_that_follows() {
        let body = r#"{"hourly":{
          "time":[1791194400,1791198000,1791201600],
          "temperature_2m":[10.0,11.0,12.0],
          "apparent_temperature":[8.0,9.0,10.0],
          "precipitation":[9.9,0.3,1.5],
          "precipitation_probability":[5,40,80],
          "weather_code":[61,3,0],
          "wind_speed_10m":[3.0,4.0,5.0],
          "wind_gusts_10m":[6.0,7.0,8.0],
          "wind_direction_10m":[90,100,110],
          "is_day":[1,0,1]}}"#;
        let steps = parse_gfs(body);
        // Three times make two steps: the last has no hour after it.
        assert_eq!(steps.len(), 2);
        assert_eq!((steps[0].rain, steps[0].chance, steps[0].gust), (0.3, Some(40.0), Some(7.0)));
        assert_eq!(steps[1].rain, 1.5);
        assert_eq!(steps[0].sky, Sky::LightRain);
        assert!(steps[1].night);
    }

    #[test]
    fn garbage_gives_empty_columns_not_a_crash() {
        for body in ["", "not json", "{}", "[]", r#"{"hourly":{"time":[1,2]}}"#, r#"{"data":null}"#] {
            assert!(parse_yr(body).is_empty());
            assert!(parse_storm(body).is_empty());
            assert!(parse_gfs(body).is_empty(), "{}", body);
        }
        assert!(!outside_usable(1, r#"{"errors":[{"message":"no"}],"data":null}"#.into()));
        assert!(outside_usable(0, yr_body()));
        let out = outside_build(Some("x".into()), None, Some("{}".into()), Some("x".into()), 59.9, 10.7, OSLO, 0);
        assert!(out.days.is_empty());
        assert_eq!(out.now, vec![None, None, None]);
        assert_eq!(out.best_day, None);
        assert!(out.warnings.is_empty());
        assert!(!outside_usable(3, "not json".into()) && !outside_usable(3, "{}".into()));
        assert!(outside_usable(3, r#"{"features":[]}"#.into()));
    }

    #[test]
    fn warnings_are_asked_for_in_norway_only() {
        assert!(outside_requests(60.39, 5.32, 0).alerts_url.ends_with("current.json?lat=60.3900&lon=5.3200&lang=en"));
        assert_eq!(outside_requests(35.68, 139.69, 0).alerts_url, "");
    }

    #[test]
    fn a_warning_marks_the_days_it_touches() {
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        let gfs = gfs_hours(start, 96, |_| (10.0, 0.0, 2.0, 3));
        let alerts = r#"{"features":[
          {"properties":{"awareness_level":"2; yellow; Moderate","eventAwarenessName":"Gale","area":"Skagerrak",
            "description":"Southwest near gale. ","instruction":"Stay ashore. ","status":"Actual"},
           "when":{"interval":["2026-10-05T09:00:00+00:00","2026-10-05T18:00:00+00:00"]}},
          {"properties":{"awareness_level":"3; orange; Severe","event":"rain","area":"Vestland","description":"Much rain."},
           "when":{"interval":["2026-10-06T20:00:00+00:00","2026-10-07T04:00:00+00:00"]}},
          {"properties":{"awareness_level":"4; red; Extreme","event":"wind","status":"Test"},
           "when":{"interval":["2026-10-05T00:00:00+00:00","2026-10-08T00:00:00+00:00"]}},
          {"properties":{"awareness_level":"2; yellow; Moderate","event":"ice"},
           "when":{"interval":["2026-10-03T00:00:00+00:00","2026-10-04T12:00:00+00:00"]}}
        ]}"#;
        let out = outside_build(None, None, Some(gfs), Some(alerts.into()), 59.91, 10.75, OSLO, start);
        // The test and the one that has ended are left out; orange comes first.
        assert_eq!(out.warnings.len(), 2);
        let (orange, yellow) = (&out.warnings[0], &out.warnings[1]);
        assert_eq!((orange.level, orange.event.as_str(), orange.area.as_str()), (3, "rain", "Vestland"));
        assert_eq!(orange.span, "Tomorrow 22:00 to Wed 7 Oct 06:00");
        assert_eq!((yellow.level, yellow.event.as_str()), (2, "Gale"));
        assert_eq!(yellow.span, "Today 11:00 to 20:00");
        assert_eq!(yellow.text, "Southwest near gale. Stay ashore.");
        let marks: Vec<u32> = out.days.iter().map(|d| d.warning).collect();
        assert_eq!(marks, vec![2, 3, 3, 0]);
    }

    /// A GFS body with one step per hour from `start`, built by `f(hour index)`
    /// returning (temp, rain in that hour, wind, weather code).
    fn gfs_hours(start: i64, count: usize, f: impl Fn(usize) -> (f64, f64, f64, i64)) -> String {
        let mut time = Vec::new();
        let (mut temp, mut rain, mut wind, mut code) = (Vec::new(), vec![0.0], Vec::new(), Vec::new());
        for i in 0..=count {
            let (t, r, w, c) = f(i);
            time.push(start + i as i64 * 3600);
            temp.push(t);
            rain.push(r);
            wind.push(w);
            code.push(c);
        }
        json!({"hourly": {"time": time, "temperature_2m": temp, "precipitation": rain,
            "wind_speed_10m": wind, "weather_code": code}})
        .to_string()
    }

    #[test]
    fn a_day_is_built_in_the_spots_own_clock() {
        // 48 hours from Oslo midnight, 5 October 2026 (22:00 UTC the 4th).
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        let gfs = gfs_hours(start, 48, |i| (10.0 + (i % 24) as f64 / 2.0, 0.0, 2.0, 0));
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, OSLO, start);
        assert_eq!(out.days.len(), 2);
        let day = &out.days[0];
        assert_eq!((day.date.as_str(), day.label.as_str()), ("2026-10-05", "Today"));
        assert_eq!(out.days[1].label, "Tomorrow");
        assert_eq!(day.rows.len(), 24);
        assert_eq!(day.rows[0].hour, 0);
        assert!(day.rows[0].cells[0].is_none() && day.rows[0].cells[2].is_some());
        let cell = day.cells[2].as_ref().unwrap();
        assert_eq!((cell.temp_min, cell.temp_max), (10.0, 21.5));
        assert_eq!(cell.sky, Sky::Clear);
        assert_eq!(day.agreement, Agreement::Single);
        // Oslo in early October: up about 07:30, down about 18:40.
        assert!(day.sunrise.starts_with("07:"), "{}", day.sunrise);
        assert!(day.sunset.starts_with("18:"), "{}", day.sunset);
        assert_eq!(out.now[2].as_ref().map(|s| s.epoch), Some(start));
    }

    #[test]
    fn hours_that_have_passed_are_dropped() {
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        let gfs = gfs_hours(start, 48, |_| (10.0, 0.0, 2.0, 3));
        // 15:30 local on the first day.
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, OSLO, start + 15 * 3600 + 1800);
        assert_eq!(out.days[0].rows.first().map(|r| r.hour), Some(15));
        assert_eq!(out.days[0].rows.len(), 9);
    }

    #[test]
    fn the_clock_change_moves_the_hours() {
        // Summer time ends in Oslo on 25 October 2026 at 01:00 UTC.
        let change = iso_epoch("2026-10-25T01:00:00Z").unwrap();
        let tz = Tz { offset: 7200, change_at: change, offset_after: 3600 };
        let start = iso_epoch("2026-10-24T22:00:00Z").unwrap(); // 00:00 local the 25th
        let gfs = gfs_hours(start, 30, |_| (5.0, 0.0, 2.0, 3));
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, tz, start - 3600);
        let day = out.days.iter().find(|d| d.date == "2026-10-25").unwrap();
        // 00, 01, 02, then 02 again as the clock goes back: the second
        // 02 replaces the first in the table, and the day has 25 hours.
        let hours: Vec<u32> = day.rows.iter().map(|r| r.hour).collect();
        assert_eq!(hours, (0..24).collect::<Vec<u32>>());
        let noon = day.rows[12].cells[2].as_ref().unwrap();
        assert_eq!(noon.epoch, iso_epoch("2026-10-25T11:00:00Z").unwrap());
    }

    #[test]
    fn the_best_stretch_is_the_dry_calm_part_of_the_day() {
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        // Rain through hour 12, dry and mild from 13 to 17, windy after that.
        let gfs = gfs_hours(start, 24, |i| match i {
            0..=12 => (12.0, 2.0, 3.0, 63),
            13..=16 => (16.0, 0.0, 3.0, 0),
            _ => (14.0, 0.0, 12.0, 3),
        });
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, OSLO, start);
        let day = &out.days[0];
        assert_eq!(day.best, "13–17");
        assert!(day.score.unwrap() >= 8, "{:?}", day.score);
        assert_eq!(out.best_day, Some(0));
    }

    #[test]
    fn a_wet_cold_day_has_no_best_stretch() {
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        let gfs = gfs_hours(start, 24, |_| (3.0, 2.5, 9.0, 65));
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, OSLO, start);
        assert_eq!(out.days[0].best, "");
        assert!(out.days[0].score.unwrap() <= 2);
        assert_eq!(out.best_day, None);
        assert_eq!(out.days[0].cells[2].as_ref().unwrap().sky, Sky::HeavyRain);
    }

    fn day(temp_max: f64, rain: f64, wind: f64) -> Option<SourceDay> {
        Some(SourceDay { temp_max, temp_min: 0.0, rain, wind, gust: None, sky: Sky::Cloudy })
    }

    #[test]
    fn agreement_names_the_biggest_difference() {
        assert_eq!(agreement(&[day(12.0, 0.0, 3.0), None, None]).0, Agreement::Single);
        assert_eq!(
            agreement(&[day(12.0, 0.2, 3.0), day(13.0, 0.0, 4.0), day(11.5, 0.4, 3.0)]),
            (Agreement::Agree, String::new())
        );
        // One says dry, one says a wet day.
        let (level, note) = agreement(&[day(12.0, 0.0, 3.0), day(12.0, 14.0, 3.0), None]);
        assert_eq!((level, note.as_str()), (Agreement::Mixed, "Rain: 0 to 14 mm"));
        // Dry against wet, and seven degrees apart.
        let (level, note) = agreement(&[day(8.0, 0.0, 3.0), day(15.0, 14.0, 3.0), day(9.0, 2.0, 8.0)]);
        assert_eq!((level, note.as_str()), (Agreement::Split, "Rain: 0 to 14 mm"));
        let (level, note) = agreement(&[day(8.0, 0.0, 3.0), day(12.0, 0.0, 3.0), None]);
        assert_eq!((level, note.as_str()), (Agreement::Mixed, "Top temperature: 8° to 12°"));
        // 2 against 6 m/s is not worth a mark; 3 against 9 is.
        assert_eq!(agreement(&[day(8.0, 0.0, 2.0), day(8.0, 0.0, 6.0), None]).0, Agreement::Agree);
        let (level, note) = agreement(&[day(8.0, 0.0, 3.0), day(8.0, 0.0, 9.0), None]);
        assert_eq!((level, note.as_str()), (Agreement::Mixed, "Wind: 3 to 9 m/s"));
        let snow = Some(SourceDay { sky: Sky::Snow, ..day(-2.0, 9.0, 3.0).unwrap() });
        assert_eq!(agreement(&[day(-2.0, 0.0, 3.0), snow, None]).1, "Rain or snow: 0 to 9.0 mm");
    }

    #[test]
    fn a_source_with_a_few_night_hours_gets_no_day_summary() {
        let start = iso_epoch("2026-10-04T22:00:00Z").unwrap();
        // 27 steps: a whole first day and three hours of the next.
        let gfs = gfs_hours(start, 27, |_| (10.0, 0.0, 2.0, 3));
        let out = outside_build(None, None, Some(gfs), None, 59.91, 10.75, OSLO, start);
        assert_eq!(out.days.len(), 1);
    }

    #[test]
    fn search_hits_and_saved_spots() {
        assert_eq!(
            outside_search_url(" Tromsø sentrum ".into()),
            "https://geocoding-api.open-meteo.com/v1/search?name=Troms%C3%B8%20sentrum&count=12&language=en&format=json"
        );
        let body = r#"{"results":[
          {"name":"Finse","latitude":60.6,"longitude":7.5,"elevation":1214.0,
           "country":"Norway","admin1":"Vestland","timezone":"Europe/Oslo"},
          {"name":"Finnset","latitude":67.13,"longitude":14.27,"elevation":9999.0,
           "country":"Norway","admin1":"Nordland","timezone":"Europe/Oslo"},
          {"name":"Nowhere"}]}"#;
        let hits = outside_search_hits(body.into());
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].region, "Vestland, Norway · 1214 m");
        assert_eq!(hits[1].region, "Nordland, Norway");
        assert_eq!(hits[0].tz, "Europe/Oslo");
        assert_eq!(outside_spots_parse(outside_spots_text(hits.clone())), hits);
        assert!(outside_spots_parse("broken".into()).is_empty());
        assert!(outside_search_hits("{}".into()).is_empty());
    }

    #[test]
    fn the_dial_for_the_widget() {
        use DialHour::{Cloud, Rain, Sun, Unknown};
        let at = |clock: &str| iso_epoch(&format!("2026-10-05T{clock}:00Z")).unwrap();

        // Yr alone: 0.4 mm from 10 UTC, partly cloudy and dry from 11,
        // then 1.2 mm over the six hours from 12.
        let yr = |now: i64| outside_dial(Some(yr_body()), None, None, OSLO, now);
        // 12:20 in Oslo. The forecast ends at 20:00 there.
        let mut want = vec![Rain, Sun, Rain, Rain, Rain, Rain, Rain, Rain, Unknown, Unknown, Unknown, Unknown];
        assert_eq!(yr(at("10:20")), want);
        // 19:30 in Oslo: one known hour is left, the one going on.
        want = vec![Unknown; 12];
        want[7] = Rain;
        assert_eq!(yr(at("17:30")), want);
        // At 20:00 the six hours from 12 are over, and Yr's other
        // six-hour step, from 13 with 1.0 mm, speaks for one more hour.
        want[7] = Unknown;
        want[8] = Rain;
        assert_eq!(yr(at("18:00")), want);
        assert_eq!(yr(at("19:00")), vec![Unknown; 12]);
        assert_eq!(outside_dial(None, None, None, OSLO, at("10:20")), vec![Unknown; 12]);

        // GFS alone, an hour a step from 12:00 in Oslo: clear, fair,
        // partly cloudy, overcast, fog, and overcast with rain.
        let codes = [0, 1, 2, 3, 45, 3];
        let gfs = gfs_hours(at("10:00"), 6, |i| (12.0, if i == 5 { 0.5 } else { 0.0 }, 3.0, codes[i.min(5)]));
        let dial = outside_dial(None, None, Some(gfs), OSLO, at("10:20"));
        assert_eq!(dial[..6], [Sun, Sun, Sun, Cloud, Cloud, Rain]);
        assert_eq!(dial[6], Unknown);

        // Two sources: rain in one is rain. Yr's hour from 11 UTC is dry
        // and partly cloudy. 0.05 mm is printed as "0.1 mm", 0.04 is not.
        let both = |mm: f64, code: i64| {
            let gfs = gfs_hours(at("10:00"), 2, |i| (12.0, if i == 1 { mm } else { 0.0 }, 3.0, code));
            outside_dial(Some(yr_body()), None, Some(gfs), OSLO, at("10:20"))[1]
        };
        assert_eq!(both(0.3, 3), Rain);
        assert_eq!(both(0.05, 3), Rain);
        assert_eq!(both(0.04, 3), Cloud);
        assert_eq!(both(0.0, 61), Rain);
        assert_eq!(both(0.0, 1), Sun);

        // A clock half an hour off UTC: its hours start on the half hour.
        // 15:50 there. The hour from 15:00 began before the forecast
        // does, and Yr's wet hour covers now.
        let india = Tz { offset: 19800, change_at: 0, offset_after: 19800 };
        let dial = outside_dial(Some(yr_body()), None, None, india, at("10:20"));
        assert_eq!(dial[3..6], [Rain, Rain, Sun]);
    }

    #[test]
    fn the_dial_is_blue_where_the_app_shows_rain() {
        // From a bug report. At 16:43 the app showed the hour from 03:00
        // as: Yr showers and 0.1 mm, Storm a cloud and 0.1 mm, GFS a rain
        // symbol and no amount. The mean is under 0.1 mm, and the ring
        // was grey from 3 to 4.
        let at = |day: u32, clock: &str| iso_epoch(&format!("2026-10-{day}T{clock}:00Z")).unwrap();
        let yr = |symbol: &str, mm: f64| {
            json!({"properties": {"timeseries": [{"time": "2026-10-11T01:00:00Z", "data": {
                "instant": {"details": {"air_temperature": 5.0, "wind_speed": 6.0}},
                "next_1_hours": {"summary": {"symbol_code": symbol},
                    "details": {"precipitation_amount": mm}}}}]}})
            .to_string()
        };
        let storm = |symbol: &str, mm: f64| {
            json!({"data": {"forecastByPlaceId": {"days": [{"weatherOneHourSteps": [{
                "startTime": "2026-10-11T01:00:00Z", "endTime": "2026-10-11T02:00:00Z",
                "temperature": 5.0, "precipitation": mm, "symbol": symbol}]}]}}})
            .to_string()
        };
        // GFS from 16:00 in Oslo, dry all the way; `code` at 03:00.
        let gfs = |code: i64| gfs_hours(at(10, "14:00"), 12, |i| (3.0, 0.0, 5.0, if i == 11 { code } else { 3 }));
        let now = at(10, "14:43");
        let hour3 = |yr: String, storm: String, gfs: String| outside_dial(Some(yr), Some(storm), Some(gfs), OSLO, now)[3];

        assert_eq!(hour3(yr("lightrainshowers_night", 0.1), storm("CLOUDY", 0.1), gfs(61)), DialHour::Rain);
        // Each column alone is enough: a printed amount under a cloud,
        // and a rain symbol with no amount.
        assert_eq!(hour3(yr("cloudy", 0.0), storm("CLOUDY", 0.1), gfs(3)), DialHour::Rain);
        assert_eq!(hour3(yr("cloudy", 0.0), storm("CLOUDY", 0.0), gfs(61)), DialHour::Rain);
        // No rain in any column: the hour is grey, as before.
        assert_eq!(hour3(yr("cloudy", 0.0), storm("CLOUDY", 0.0), gfs(3)), DialHour::Cloud);
        // The hours before it have only GFS, overcast and dry.
        assert_eq!(outside_dial(None, None, Some(gfs(61)), OSLO, now)[..3], [DialHour::Cloud; 3]);
    }

    #[test]
    fn the_sky_for_the_widget() {
        // Oslo, 9 October 2026 at 23:20, summer time. New moon came 18
        // hours later.
        let s = outside_sky(2026, 10, 9, 23.0 + 20.0 / 60.0, 59.91, 10.75, 2.0);
        assert_eq!(s.sign, "LIBRA");
        assert_eq!(s.phase, "Waning Crescent");
        assert!(s.lit <= 2, "lit {}", s.lit);
        assert!(s.sun.starts_with("07:4") && s.sun.contains(" - 18:2"), "{}", s.sun);
        assert!(s.moon.starts_with("05:5") && s.moon.contains(" - 17:"), "{}", s.moon);
        assert!((s.sunrise - 7.7).abs() < 0.1 && (s.sunset - 18.5).abs() < 0.1);

        // Full moon was 26 October at 05:12 Oslo time, the first quarter
        // 18 October at 18:13.
        let full = outside_sky(2026, 10, 26, 5.2, 59.91, 10.75, 1.0);
        assert_eq!((full.phase.as_str(), full.lit), ("Full Moon", 100));
        assert_eq!(full.sign, "SCORPIO");
        let half = outside_sky(2026, 10, 18, 18.2, 59.91, 10.75, 2.0);
        assert_eq!(half.phase, "First Quarter");
        assert!((49..=51).contains(&half.lit), "lit {}", half.lit);
        // Two days on it is past the quarter.
        assert_eq!(outside_sky(2026, 10, 20, 18.2, 59.91, 10.75, 2.0).phase, "Waxing Gibbous");

        // The sun crossed into Aries on 20 March 2026 at 15:46 Oslo time.
        assert_eq!(outside_sky(2026, 3, 20, 12.0, 59.91, 10.75, 1.0).sign, "PISCES");
        assert_eq!(outside_sky(2026, 3, 20, 18.0, 59.91, 10.75, 1.0).sign, "ARIES");

        // Tromsø at midsummer: the sun does not set, so there is no line.
        let north = outside_sky(2026, 6, 21, 12.0, 69.65, 18.96, 2.0);
        assert_eq!((north.sun.as_str(), north.sunrise, north.sunset), ("", -1.0, -1.0));
        // The sun had crossed into Cancer that morning, at 10:24.
        assert_eq!(north.sign, "CANCER");
        assert_eq!(outside_sky(2026, 6, 21, 9.0, 69.65, 18.96, 2.0).sign, "GEMINI");
    }
}
