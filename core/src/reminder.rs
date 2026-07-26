// Reminders on hyperlist items.
//
// HyperList stamps an item with `YYYY-MM-DD HH.MM: text` — note the period
// between hours and minutes, which is what distinguishes a timestamp from
// the colon that ends it. A checkbox may lead:
//
//     2026-07-27 12.08: Call the dentist
//     [x] 2026-07-27 12.08: Call the dentist      (done — never fires)
//
// This module owns the reading and writing of that form, plus turning a
// spoken sentence into it, so tasks and vox agree byte for byte.
//
// Deliberately timezone-free: a Stamp is civil date and time, exactly what
// the file holds. The phone's zone is a platform concern, so the Kotlin
// side converts a Stamp to epoch millis with the device's ZoneId. Nothing
// here needs a tz database.

use crate::hyperlist::Hyperlist;

/// A civil date and time, as written in the file. No zone, no seconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Stamp {
    pub year: i32,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
}

/// One item, split into its stamp (if any) and the text that follows.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct StampedItem {
    pub stamp: Option<Stamp>,
    /// The item without its stamp or checkbox — what a notification shows.
    pub text: String,
    /// `[x]` items are history, not reminders.
    pub done: bool,
}

/// A due reminder, with the category it came from so the notification can
/// say where it lives.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Reminder {
    pub stamp: Stamp,
    pub text: String,
    pub category: String,
}

impl Stamp {
    /// Sortable key. Not a timestamp — only ever compared with another
    /// Stamp, so the arbitrary base does not matter.
    fn key(&self) -> i64 {
        ((self.year as i64) << 40)
            | ((self.month as i64) << 32)
            | ((self.day as i64) << 24)
            | ((self.hour as i64) << 16)
            | (self.minute as i64)
    }
}

fn two(s: &str) -> Option<u32> {
    if s.len() == 2 && s.bytes().all(|b| b.is_ascii_digit()) {
        s.parse().ok()
    } else {
        None
    }
}

/// Split an item into its stamp and its text.
///
/// Accepts `YYYY-MM-DD HH.MM: text`, with an optional leading `[ ]` or
/// `[x]` checkbox. Anything else comes back as plain text with no stamp,
/// so an unstamped item is simply never scheduled.
#[uniffi::export]
pub fn parse_item(line: String) -> StampedItem {
    let mut rest = line.trim();
    let mut done = false;
    // Optional checkbox.
    if let Some(after) = rest.strip_prefix('[') {
        if let Some((mark, tail)) = after.split_once(']') {
            let m = mark.trim();
            if m.is_empty() || m.eq_ignore_ascii_case("x") || m == "_" || m == "O" {
                done = m.eq_ignore_ascii_case("x");
                rest = tail.trim_start();
            }
        }
    }
    let unstamped = |text: &str| StampedItem {
        stamp: None,
        text: text.to_string(),
        done,
    };
    // "2026-07-27 12.08: text"
    let Some((date, tail)) = rest.split_once(' ') else {
        return unstamped(rest);
    };
    let d: Vec<&str> = date.split('-').collect();
    if d.len() != 3 || d[0].len() != 4 {
        return unstamped(rest);
    }
    let (Ok(year), Some(month), Some(day)) = (d[0].parse::<i32>(), two(d[1]), two(d[2])) else {
        return unstamped(rest);
    };
    let Some((clock, text)) = tail.split_once(american_colon()) else {
        return unstamped(rest);
    };
    let Some((h, m)) = clock.trim().split_once('.') else {
        return unstamped(rest);
    };
    let (Some(hour), Some(minute)) = (two(h), two(m)) else {
        return unstamped(rest);
    };
    if month == 0 || month > 12 || day == 0 || day > 31 || hour > 23 || minute > 59 {
        return unstamped(rest);
    }
    StampedItem {
        stamp: Some(Stamp { year, month, day, hour, minute }),
        text: text.trim().to_string(),
        done,
    }
}

/// The separator between the clock and the text. Named so the `.` vs `:`
/// distinction is impossible to misread at the call site.
fn american_colon() -> char {
    ':'
}

/// Render a stamp and text back into an item line.
#[uniffi::export]
pub fn format_item(stamp: Stamp, text: String) -> String {
    format!(
        "{:04}-{:02}-{:02} {:02}.{:02}: {}",
        stamp.year, stamp.month, stamp.day, stamp.hour, stamp.minute, text.trim()
    )
}

/// Every stamped, unfinished item in the list, earliest first. The Kotlin
/// side turns these into alarms.
#[uniffi::export]
pub fn list_reminders(hl: Hyperlist) -> Vec<Reminder> {
    let mut out: Vec<Reminder> = Vec::new();
    for cat in &hl.categories {
        for item in &cat.items {
            let parsed = parse_item(item.text.clone());
            if parsed.done {
                continue;
            }
            if let Some(stamp) = parsed.stamp {
                out.push(Reminder {
                    stamp,
                    text: parsed.text,
                    category: cat.name.clone(),
                });
            }
        }
    }
    out.sort_by_key(|r| r.stamp.key());
    out
}

// -------------------- spoken → stamped item --------------------

const WEEKDAYS: [&str; 7] = [
    "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
];

/// Days in a month, Gregorian.
fn days_in_month(year: i32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ => {
            let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
            if leap { 29 } else { 28 }
        }
    }
}

fn add_days(mut s: Stamp, mut n: u32) -> Stamp {
    while n > 0 {
        let dim = days_in_month(s.year, s.month);
        if s.day < dim {
            s.day += 1;
        } else {
            s.day = 1;
            if s.month == 12 {
                s.month = 1;
                s.year += 1;
            } else {
                s.month += 1;
            }
        }
        n -= 1;
    }
    s
}

/// Day of week, 0 = Sunday. Sakamoto's method.
fn weekday(s: Stamp) -> u32 {
    const T: [i32; 12] = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
    let mut y = s.year;
    if s.month < 3 {
        y -= 1;
    }
    let w = (y + y / 4 - y / 100 + y / 400 + T[(s.month - 1) as usize] + s.day as i32) % 7;
    w as u32
}

fn add_minutes(s: Stamp, n: u32) -> Stamp {
    let total = s.hour * 60 + s.minute + n;
    let mut out = add_days(s, total / (24 * 60));
    out.hour = (total % (24 * 60)) / 60;
    out.minute = total % 60;
    out
}

/// Words that introduce the errand rather than belong to it.
const LEAD_FILLER: [&str; 10] = [
    "that i", "that i'll", "that i will", "that", "to", "about", "for", "i", "of", ":",
];

/// Words that belong to the time expression, trimmed off a payload that
/// sits before it ("remind me to call the dentist tomorrow at").
const TRAIL_FILLER: [&str; 12] = [
    "at", "on", "by", "tomorrow", "today", "tonight", "morning", "afternoon",
    "evening", "next", "this", "oclock",
];

fn strip_lead(s: &str) -> String {
    let mut cur = s.trim().trim_start_matches([',', '.', ':', ';']).trim().to_string();
    loop {
        let low = cur.to_lowercase();
        let mut cut = 0usize;
        for f in LEAD_FILLER {
            if low == f {
                cut = cur.len();
                break;
            }
            if let Some(rest) = low.strip_prefix(f) {
                if rest.starts_with(' ') && f.len() > cut {
                    cut = f.len();
                }
            }
        }
        if cut == 0 {
            return cur;
        }
        cur = cur[cut..].trim().trim_start_matches([',', ':']).trim().to_string();
    }
}

fn strip_trail(s: &str) -> String {
    let mut cur = s.trim().trim_end_matches([',', '.', ':', ';']).trim().to_string();
    loop {
        let low = cur.to_lowercase();
        let last = match low.rsplit_once(' ') {
            Some((_, w)) => w.to_string(),
            None => low.clone(),
        };
        let is_filler = TRAIL_FILLER.contains(&last.as_str())
            || WEEKDAYS.contains(&last.as_str())
            || last.trim_end_matches("'clock") != last;
        if !is_filler {
            return cur;
        }
        cur = match cur.rsplit_once(' ') {
            Some((head, _)) => head.trim().trim_end_matches(',').trim().to_string(),
            None => return String::new(),
        };
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// A clock time found in the words, with the token span it occupied.
struct FoundTime {
    hour: u32,
    minute: u32,
    start: usize,
    end: usize,
}

fn parse_clock_token(tok: &str) -> Option<(u32, u32)> {
    let t = tok.trim_matches(|c: char| !c.is_ascii_digit() && c != ':' && c != '.');
    if let Some((h, m)) = t.split_once([':', '.']) {
        let (h, m) = (h.parse::<u32>().ok()?, m.parse::<u32>().ok()?);
        if h < 24 && m < 60 {
            return Some((h, m));
        }
    }
    None
}

/// Find the time of day in the spoken words: "12:08", "12.08", "12 08",
/// "at 12", "12 o'clock".
fn find_time(words: &[String]) -> Option<FoundTime> {
    for (i, w) in words.iter().enumerate() {
        if let Some((hour, minute)) = parse_clock_token(w) {
            return Some(FoundTime { hour, minute, start: i, end: i });
        }
    }
    // "at 12 08" — Whisper writes spoken digit pairs as separate numbers.
    for i in 0..words.len() {
        let Ok(h) = words[i].trim_matches(|c: char| !c.is_ascii_digit()).parse::<u32>() else {
            continue;
        };
        if h > 23 {
            continue;
        }
        let anchored = i > 0 && {
            let p = words[i - 1].to_lowercase();
            p == "at" || p == "kl" || p == "around"
        };
        let next = words.get(i + 1).map(|w| w.trim_matches(|c: char| !c.is_ascii_digit()));
        if let Some(n) = next {
            // A two-digit second number is the minutes ("12 08"), but only
            // when it really looks like one: "call 3 people" must not read
            // as a time.
            if n.len() == 2 {
                if let Ok(m) = n.parse::<u32>() {
                    if m < 60 && (anchored || h >= 13) {
                        return Some(FoundTime { hour: h, minute: m, start: i, end: i + 1 });
                    }
                }
            }
        }
        let followed_by_oclock = words
            .get(i + 1)
            .map(|w| w.to_lowercase().starts_with("o'clock") || w.to_lowercase().starts_with("oclock"))
            .unwrap_or(false);
        if anchored || followed_by_oclock {
            let end = if followed_by_oclock { i + 1 } else { i };
            return Some(FoundTime { hour: h, minute: 0, start: i, end });
        }
    }
    None
}

/// Turn a spoken sentence into a hyperlist item, stamped if it named a
/// time: "Remind me tomorrow at 12 08 that I call Alice" becomes
/// "2026-07-27 12.08: Call Alice".
///
/// `now` is the phone's current local date and time. A sentence with no
/// recognisable time comes back as itself, so vox files it as a plain
/// inbox line exactly as before.
#[uniffi::export]
pub fn spoken_to_item(transcript: String, now: Stamp) -> String {
    let words: Vec<String> = transcript.split_whitespace().map(|w| w.to_string()).collect();
    if words.is_empty() {
        return String::new();
    }
    let low: Vec<String> = words.iter().map(|w| w.to_lowercase()).collect();

    // "in 20 minutes" / "in two hours" — relative, no clock needed.
    if let Some(i) = low.iter().position(|w| w == "in") {
        if let Some(n) = low.get(i + 1).and_then(|w| word_number(w)) {
            if let Some(unit) = low.get(i + 2) {
                let mins = if unit.starts_with("hour") {
                    Some(n * 60)
                } else if unit.starts_with("min") {
                    Some(n)
                } else {
                    None
                };
                if let Some(mins) = mins {
                    let stamp = add_minutes(now, mins);
                    let payload = payload_around(&words, i, i + 2);
                    return format_item(stamp, payload);
                }
            }
        }
    }

    let Some(t) = find_time(&low) else {
        return transcript.trim().to_string();
    };

    // Which day? tomorrow, a named weekday, or today (rolling over when
    // the time has already gone by).
    let mut stamp = Stamp { hour: t.hour, minute: t.minute, ..now };
    if low.iter().any(|w| w.starts_with("tomorrow")) {
        stamp = Stamp { hour: t.hour, minute: t.minute, ..add_days(now, 1) };
    } else if let Some(target) = low.iter().find_map(|w| {
        let w = w.trim_matches(|c: char| !c.is_alphabetic());
        WEEKDAYS.iter().position(|d| *d == w)
    }) {
        let today = weekday(now) as usize;
        let mut ahead = (target + 7 - today) % 7;
        if ahead == 0 {
            ahead = 7; // "on friday" said on a Friday means the next one
        }
        stamp = Stamp { hour: t.hour, minute: t.minute, ..add_days(now, ahead as u32) };
    } else if !low.iter().any(|w| w.starts_with("today") || w.starts_with("tonight"))
        && (t.hour * 60 + t.minute) <= (now.hour * 60 + now.minute)
    {
        // A bare time that has already passed means tomorrow.
        stamp = Stamp { hour: t.hour, minute: t.minute, ..add_days(now, 1) };
    }

    format_item(stamp, payload_around(&words, t.start, t.end))
}

/// The errand itself: what follows the time expression, or what precedes
/// it when the sentence puts the time last.
fn payload_around(words: &[String], start: usize, end: usize) -> String {
    let after = strip_lead(&words[(end + 1).min(words.len())..].join(" "));
    if !after.is_empty() {
        return capitalize(&after);
    }
    let before = words[..start].join(" ");
    let before = strip_trail(&before);
    // Drop the command itself.
    let low = before.to_lowercase();
    let cut = ["remind me", "reminder", "remember"]
        .iter()
        .find_map(|p| low.find(p).map(|i| i + p.len()))
        .unwrap_or(0);
    capitalize(&strip_lead(&before[cut..]))
}

/// Small spelled-out numbers, since Whisper writes some of them as words.
fn word_number(w: &str) -> Option<u32> {
    const NAMES: [&str; 21] = [
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen", "twenty",
    ];
    let w = w.trim_matches(|c: char| !c.is_alphanumeric());
    if let Ok(n) = w.parse::<u32>() {
        return Some(n);
    }
    NAMES.iter().position(|n| *n == w).map(|i| i as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2026-07-26 was a Sunday.
    const NOW: Stamp = Stamp { year: 2026, month: 7, day: 26, hour: 10, minute: 0 };

    #[test]
    fn reads_a_stamped_item() {
        let p = parse_item("2026-07-27 12.08: Call Alice".into());
        assert_eq!(p.stamp, Some(Stamp { year: 2026, month: 7, day: 27, hour: 12, minute: 8 }));
        assert_eq!(p.text, "Call Alice");
        assert!(!p.done);
    }

    #[test]
    fn reads_a_checked_item_and_marks_it_done() {
        let p = parse_item("[x] 2026-07-27 12.08: Call Alice".into());
        assert!(p.done);
        assert_eq!(p.text, "Call Alice");
        let open = parse_item("[_] 2026-07-27 12.08: Call Alice".into());
        assert!(!open.done);
        assert!(open.stamp.is_some());
    }

    #[test]
    fn leaves_plain_items_alone() {
        for line in [
            "Call Alice",
            "12.08: no date",
            "2026-07-27: no clock",
            "2026-07-27 12:08: colon is not the hyperlist form",
            "Buy 2 litres of milk",
        ] {
            let p = parse_item(line.to_string());
            assert!(p.stamp.is_none(), "{line:?} should not parse as stamped");
            assert_eq!(p.text, line);
        }
    }

    #[test]
    fn round_trips() {
        let s = Stamp { year: 2026, month: 7, day: 27, hour: 12, minute: 8 };
        let line = format_item(s, "Call Alice".into());
        assert_eq!(line, "2026-07-27 12.08: Call Alice");
        assert_eq!(parse_item(line).stamp, Some(s));
    }

    #[test]
    fn spoken_tomorrow_with_spaced_digits() {
        // The sentence from the request, verbatim.
        let line = spoken_to_item("Remind me tomorrow at 12 08 that I call Alice".into(), NOW);
        assert_eq!(line, "2026-07-27 12.08: Call Alice");
    }

    #[test]
    fn spoken_variants() {
        let cases = [
            ("Remind me at 15:30 to buy milk", "2026-07-26 15.30: Buy milk"),
            ("remind me tomorrow at 9 to water the plants", "2026-07-27 09.00: Water the plants"),
            ("remind me to call the dentist tomorrow at 14 30", "2026-07-27 14.30: Call the dentist"),
            ("remind me on friday at 8 to pack", "2026-07-31 08.00: Pack"),
            ("remind me in 20 minutes to check the oven", "2026-07-26 10.20: Check the oven"),
            ("remind me in two hours to call back", "2026-07-26 12.00: Call back"),
        ];
        for (spoken, want) in cases {
            assert_eq!(spoken_to_item(spoken.into(), NOW), want, "for {spoken:?}");
        }
    }

    #[test]
    fn a_time_already_gone_means_tomorrow() {
        // 08:00 said at 10:00.
        let line = spoken_to_item("remind me at 08:00 to stretch".into(), NOW);
        assert_eq!(line, "2026-07-27 08.00: Stretch");
    }

    #[test]
    fn no_time_no_stamp() {
        for plain in [
            "buy milk",
            "call the dentist about the appointment",
            "remember to bring 3 books",
        ] {
            assert_eq!(spoken_to_item(plain.into(), NOW), plain);
        }
    }

    #[test]
    fn reminders_are_listed_in_order_and_skip_done() {
        let hl = crate::hyperlist::parse(
            "\tInbox\n\t\t2026-08-01 09.00: Later\n\t\t2026-07-27 12.08: Sooner\n\
             \t\t[x] 2026-07-27 07.00: Already done\n\t\tNo stamp here\n"
                .to_string(),
        );
        let r = list_reminders(hl);
        assert_eq!(r.len(), 2);
        assert_eq!(r[0].text, "Sooner");
        assert_eq!(r[1].text, "Later");
        assert_eq!(r[0].category, "Inbox");
    }

    #[test]
    fn month_and_year_roll_over() {
        let dec = Stamp { year: 2026, month: 12, day: 31, hour: 23, minute: 50 };
        assert_eq!(add_minutes(dec, 20), Stamp { year: 2027, month: 1, day: 1, hour: 0, minute: 10 });
        let feb = Stamp { year: 2028, month: 2, day: 28, hour: 12, minute: 0 };
        assert_eq!(add_days(feb, 1).day, 29); // 2028 is a leap year
    }
}
