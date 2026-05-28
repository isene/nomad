// Weather: pure parsing of met.no locationforecast JSON + observing-condition
// rules, ported from astro/src/weather.rs. The HTTP GET happens in Kotlin
// (OkHttp); this takes the raw response body and aggregates the timeseries
// into per-day forecasts.

use serde_json::Value as JsonValue;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HourPoint {
    pub time: String,
    pub date: String,
    pub hour: i64,
    pub hour_str: String,
    pub temp: f64,
    pub wind: f64,
    pub gust: f64,
    pub wind_dir: i64,
    pub wind_dir_name: String,
    pub cloud: i64,
    pub cloud_low: i64,
    pub cloud_high: i64,
    pub fog: f64,
    pub humidity: f64,
    pub dew_point: f64,
    pub pressure: f64,
    pub uv: f64,
    pub precip: f64,
    pub symbol: String, // raw met.no code, e.g. "partlycloudy_day"
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DayForecast {
    pub date: String,
    pub temp_high: f64,
    pub temp_low: f64,
    pub temp_mid: f64,
    pub wind: f64,
    pub cloud: i64,
    pub humidity: f64,
    pub symbol: String, // emoji for the day (from midday code)
    pub hours: Vec<HourPoint>,
}

/// Observing-condition limits (from config). Higher condition points = worse.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ConditionLimits {
    pub cloud_limit: i64,
    pub humidity_limit: f64,
    pub temp_limit: f64,
    pub wind_limit: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ConditionLevel {
    Good, // 0-1
    Fair, // 2-3
    Poor, // 4+
}

fn wind_dir_name(deg: i64) -> &'static str {
    match ((deg.rem_euclid(360)) / 45) as usize {
        0 => "N", 1 => "NE", 2 => "E", 3 => "SE",
        4 => "S", 5 => "SW", 6 => "W", 7 => "NW",
        _ => "N",
    }
}

fn symbol_char_from_code(code: &str) -> &'static str {
    match code.split('_').next().unwrap_or(code) {
        "clearsky" | "fair" => "\u{2600}",
        "partlycloudy" => "\u{26C5}",
        "cloudy" => "\u{2601}",
        "fog" => "\u{1F32B}",
        "lightrain" | "lightrainshowers" => "\u{1F326}",
        "rain" | "rainshowers" | "heavyrain" | "heavyrainshowers" => "\u{1F327}",
        "snow" | "snowshowers" | "lightsnow" | "heavysnow" => "\u{1F328}",
        "sleet" | "lightsleet" | "heavysleet" => "\u{1F328}",
        "thunderstorm" | "heavyrainandthunder" => "\u{26C8}",
        _ => "\u{26C5}",
    }
}

/// Parse the raw met.no locationforecast/2.0/complete JSON body into per-day
/// forecasts. Empty vec on malformed input.
#[uniffi::export]
pub fn parse_weather(json: String) -> Vec<DayForecast> {
    let body: JsonValue = match serde_json::from_str(&json) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    let timeseries = match body.pointer("/properties/timeseries") {
        Some(JsonValue::Array(arr)) => arr,
        _ => return Vec::new(),
    };

    let mut by_date: std::collections::BTreeMap<String, Vec<HourPoint>> =
        std::collections::BTreeMap::new();

    for ts in timeseries {
        let time = match ts.get("time").and_then(|v| v.as_str()) {
            Some(t) => t.to_string(),
            None => continue,
        };
        let details = match ts.pointer("/data/instant/details") {
            Some(d) => d,
            None => continue,
        };
        let next_1h = ts.pointer("/data/next_1_hours");
        if time.len() < 13 {
            continue;
        }
        let date = time[..10].to_string();
        let hour: i64 = time[11..13].parse().unwrap_or(-1);
        let f = |k: &str| details.get(k).and_then(|v| v.as_f64()).unwrap_or(0.0);

        let cloud = f("cloud_area_fraction") as i64;
        let precip = next_1h
            .and_then(|n| n.pointer("/details/precipitation_amount"))
            .and_then(|v| v.as_f64())
            .unwrap_or(0.0);
        let symbol = next_1h
            .and_then(|n| n.pointer("/summary/symbol_code"))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_else(|| "fair".into());
        let wind_dir = f("wind_from_direction") as i64;

        by_date.entry(date.clone()).or_default().push(HourPoint {
            time: time.clone(),
            date: date.clone(),
            hour,
            hour_str: format!("{:02}", hour),
            temp: f("air_temperature"),
            wind: f("wind_speed"),
            gust: f("wind_speed_of_gust"),
            wind_dir,
            wind_dir_name: wind_dir_name(wind_dir).to_string(),
            cloud,
            cloud_low: f("cloud_area_fraction_low") as i64,
            cloud_high: f("cloud_area_fraction_high") as i64,
            fog: f("fog_area_fraction"),
            humidity: f("relative_humidity"),
            dew_point: f("dew_point_temperature"),
            pressure: f("air_pressure_at_sea_level"),
            uv: f("ultraviolet_index_clear_sky"),
            precip,
            symbol,
        });
    }

    let mut days = Vec::new();
    for (date, hours) in by_date {
        if hours.is_empty() {
            continue;
        }
        let temps: Vec<f64> = hours.iter().map(|h| h.temp).collect();
        let temp_high = (temps.iter().cloned().fold(f64::NEG_INFINITY, f64::max) * 10.0).round() / 10.0;
        let temp_low = (temps.iter().cloned().fold(f64::INFINITY, f64::min) * 10.0).round() / 10.0;
        let mid_idx = hours.iter().position(|h| h.hour == 12).unwrap_or(hours.len() / 2);
        let midday = &hours[mid_idx];
        let humidity: f64 = hours.iter().map(|h| h.humidity).sum::<f64>() / hours.len() as f64;
        days.push(DayForecast {
            date,
            temp_high,
            temp_low,
            temp_mid: (midday.temp * 10.0).round() / 10.0,
            wind: midday.wind,
            cloud: midday.cloud,
            humidity: (humidity * 10.0).round() / 10.0,
            symbol: symbol_char_from_code(&midday.symbol).to_string(),
            hours,
        });
    }
    days
}

/// Astropanel condition points (higher = worse). 0-1 good, 2-3 fair, 4+ poor.
#[uniffi::export]
pub fn condition_points(cloud: i64, humidity: f64, temp: f64, wind: f64, limits: ConditionLimits) -> i32 {
    let mut p = 0;
    if cloud > limits.cloud_limit { p += 2; }
    if (cloud as f64) > (100.0 - limits.cloud_limit as f64) / 2.0 { p += 1; }
    if cloud > 90 { p += 1; }
    if humidity > limits.humidity_limit { p += 1; }
    if temp < limits.temp_limit { p += 1; }
    if temp < limits.temp_limit - 7.0 { p += 1; }
    if wind > limits.wind_limit { p += 1; }
    if wind > 2.0 * limits.wind_limit { p += 1; }
    p
}

#[uniffi::export]
pub fn condition_level(points: i32) -> ConditionLevel {
    if points >= 4 {
        ConditionLevel::Poor
    } else if points >= 2 {
        ConditionLevel::Fair
    } else {
        ConditionLevel::Good
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_met_no_timeseries() {
        let json = r#"{
          "properties": { "timeseries": [
            {"time":"2026-05-28T12:00:00Z","data":{
              "instant":{"details":{"air_temperature":14.2,"wind_speed":3.0,
                "wind_speed_of_gust":5.0,"wind_from_direction":180.0,
                "cloud_area_fraction":20.0,"relative_humidity":55.0,
                "dew_point_temperature":5.0,"air_pressure_at_sea_level":1015.0,
                "fog_area_fraction":0.0,"ultraviolet_index_clear_sky":3.0}},
              "next_1_hours":{"summary":{"symbol_code":"partlycloudy_day"},
                "details":{"precipitation_amount":0.0}}}},
            {"time":"2026-05-28T13:00:00Z","data":{
              "instant":{"details":{"air_temperature":16.0,"wind_speed":4.0,
                "wind_speed_of_gust":6.0,"wind_from_direction":90.0,
                "cloud_area_fraction":80.0,"relative_humidity":60.0,
                "dew_point_temperature":6.0,"air_pressure_at_sea_level":1014.0,
                "fog_area_fraction":0.0,"ultraviolet_index_clear_sky":2.0}},
              "next_1_hours":{"summary":{"symbol_code":"cloudy"},
                "details":{"precipitation_amount":0.1}}}}
          ]}}"#;
        let days = parse_weather(json.to_string());
        assert_eq!(days.len(), 1);
        let d = &days[0];
        assert_eq!(d.date, "2026-05-28");
        assert_eq!(d.hours.len(), 2);
        assert_eq!(d.temp_high, 16.0);
        assert_eq!(d.temp_low, 14.2);
        assert_eq!(d.temp_mid, 14.2); // hour 12 is the midday pick
        assert_eq!(d.hours[0].wind_dir_name, "S");
        assert_eq!(d.hours[1].wind_dir_name, "E");
    }

    #[test]
    fn malformed_weather_is_empty() {
        assert!(parse_weather("not json".into()).is_empty());
        assert!(parse_weather("{}".into()).is_empty());
    }

    #[test]
    fn conditions() {
        let limits = ConditionLimits { cloud_limit: 40, humidity_limit: 80.0, temp_limit: -10.0, wind_limit: 8.0 };
        // clear, dry, calm -> good
        assert_eq!(condition_level(condition_points(10, 50.0, 5.0, 2.0, limits)), ConditionLevel::Good);
        // overcast + humid + windy -> poor
        let p = condition_points(95, 90.0, -12.0, 18.0, limits);
        assert!(p >= 4);
        assert_eq!(condition_level(p), ConditionLevel::Poor);
    }
}
