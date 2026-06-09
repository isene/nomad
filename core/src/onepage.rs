// OnePage launcher — widget layout model.
//
// The launcher's only persistent state: where each hosted AppWidget sits on
// the single home page. Free placement, no grid, overlap allowed, so the
// model is just absolute (x, y, w, h) in px per widget. Kotlin owns the file
// I/O (filesDir/layout.json, atomic write); this module owns the format, so
// desktop tools can read/edit a layout file later if useful.

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
pub struct WidgetPos {
    pub app_widget_id: i32,
    /// Provider ComponentName as a flat string ("pkg/cls"). Informational —
    /// rebinding after restore goes through the appWidgetId, but the provider
    /// string lets a future import flow re-bind on a new device.
    pub provider: String,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
pub struct Layout {
    pub version: u32,
    pub widgets: Vec<WidgetPos>,
}

#[uniffi::export]
pub fn onepage_empty() -> Layout {
    Layout { version: 1, widgets: vec![] }
}

/// Parse a layout file. None on malformed JSON — the caller falls back to an
/// empty layout rather than crashing the launcher at boot.
#[uniffi::export]
pub fn onepage_parse(json: String) -> Option<Layout> {
    serde_json::from_str(&json).ok()
}

#[uniffi::export]
pub fn onepage_serialize(layout: Layout) -> String {
    serde_json::to_string_pretty(&layout).unwrap_or_else(|_| "{}".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Layout {
        Layout {
            version: 1,
            widgets: vec![
                WidgetPos {
                    app_widget_id: 17,
                    provider: "com.android.calendar/.CalWidget".into(),
                    x: 40,
                    y: 200,
                    w: 800,
                    h: 600,
                },
                WidgetPos {
                    app_widget_id: 18,
                    provider: "com.example.clock/.Big".into(),
                    x: 0,
                    y: 0,
                    w: 400,
                    h: 200,
                },
            ],
        }
    }

    #[test]
    fn roundtrip() {
        let l = sample();
        let json = onepage_serialize(l.clone());
        let back = onepage_parse(json).unwrap();
        assert_eq!(back, l);
    }

    #[test]
    fn parse_spec_example() {
        let json = r#"{
            "version": 1,
            "widgets": [
                { "app_widget_id": 17, "provider": "com.android.calendar/.CalWidget",
                  "x": 40, "y": 200, "w": 800, "h": 600 }
            ]
        }"#;
        let l = onepage_parse(json.into()).unwrap();
        assert_eq!(l.widgets.len(), 1);
        assert_eq!(l.widgets[0].app_widget_id, 17);
        assert_eq!(l.widgets[0].h, 600);
    }

    #[test]
    fn malformed_returns_none() {
        assert!(onepage_parse("not json".into()).is_none());
        assert!(onepage_parse("".into()).is_none());
    }

    #[test]
    fn empty_layout() {
        let e = onepage_empty();
        assert_eq!(e.version, 1);
        assert!(e.widgets.is_empty());
        let back = onepage_parse(onepage_serialize(e.clone())).unwrap();
        assert_eq!(back, e);
    }
}
