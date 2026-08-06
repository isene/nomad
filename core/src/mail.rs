// Mail for the phone: a thin UniFFI skin over the fe2o3-mail crate.
//
// Nothing is decided here. Decoding a body, deciding what has been read
// — that logic is shared with desktop kastrup so the two cannot drift,
// and this file only makes it reachable from Kotlin.

use std::collections::HashMap;

/// One message as the phone stores and shows it. Bodies are kept raw so
/// the decode happens on display and a re-decode costs nothing if the
/// rules improve.
#[derive(Debug, Clone, PartialEq, Default, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Mail {
    /// RFC822 Message-ID — the identity that survives maildir, IMAP and
    /// this phone, and the key read state is agreed on.
    pub message_id: String,
    /// Which account it arrived in, so a reply can go back out the same way.
    pub account: String,
    pub folder: String,
    pub from: String,
    pub to: String,
    pub subject: String,
    /// Unix seconds.
    pub date: i64,
    /// The raw body, still encoded. `body_text` turns it into something
    /// to read.
    pub raw: String,
    #[serde(default)]
    pub html: String,
    #[serde(default)]
    pub has_attachments: bool,
}

#[uniffi::export]
pub fn parse_mails(json: String) -> Vec<Mail> {
    serde_json::from_str(&json).unwrap_or_default()
}

#[uniffi::export]
pub fn serialize_mails(mails: Vec<Mail>) -> String {
    serde_json::to_string(&mails).unwrap_or_else(|_| "[]".into())
}

/// The readable text of a message.
#[uniffi::export]
pub fn mail_body_text(raw: String, html: String) -> String {
    let fallback = if html.trim().is_empty() { None } else { Some(html.as_str()) };
    mail::body_text(&raw, fallback)
}

/// Decode RFC 2047 encoded words in a header (`=?UTF-8?B?...?=`).
#[uniffi::export]
pub fn mail_decode_header(s: String) -> String {
    mail::decode_rfc2047(&s)
}

/// One device's read-state file, flattened for the FFI.
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct ReadMark {
    pub message_id: String,
    pub read: bool,
    pub ts: i64,
}

fn to_marks(v: Vec<ReadMark>) -> mail::read_state::Marks {
    v.into_iter()
        .map(|m| (m.message_id, mail::read_state::Mark { read: m.read, ts: m.ts }))
        .collect()
}

fn from_marks(m: mail::read_state::Marks) -> Vec<ReadMark> {
    let mut out: Vec<ReadMark> = m.into_iter()
        .map(|(message_id, v)| ReadMark { message_id, read: v.read, ts: v.ts })
        .collect();
    out.sort_by(|a, b| b.ts.cmp(&a.ts));
    out
}

#[uniffi::export]
pub fn parse_read_marks(json: String) -> Vec<ReadMark> {
    from_marks(mail::read_state::parse(&json))
}

#[uniffi::export]
pub fn serialize_read_marks(marks: Vec<ReadMark>) -> String {
    mail::read_state::serialize(&to_marks(marks))
}

/// Merge every device's file. Newest timestamp per message wins.
#[uniffi::export]
pub fn merge_read_marks(files: Vec<String>) -> Vec<ReadMark> {
    let mut all: mail::read_state::Marks = HashMap::new();
    for json in &files {
        mail::read_state::merge_into(&mut all, mail::read_state::parse(json));
    }
    from_marks(all)
}

/// Has this message been read? Unknown means unread.
#[uniffi::export]
pub fn is_mail_read(marks: Vec<ReadMark>, message_id: String) -> bool {
    to_marks(marks).get(&message_id).map(|m| m.read).unwrap_or(false)
}

/// Record an explicit mark. This phone publishes ONLY these — opening a
/// message writes nothing, which is what keeps the laptop authoritative.
#[uniffi::export]
pub fn set_read_mark(marks: Vec<ReadMark>, message_id: String, read: bool, now: i64) -> Vec<ReadMark> {
    let mut m = to_marks(marks);
    mail::read_state::set(&mut m, &message_id, read, now);
    from_marks(m)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_mail_round_trips() {
        let m = Mail {
            message_id: "a@x".into(), account: "geir@isene.com".into(),
            folder: "INBOX".into(), from: "Someone <s@x>".into(), to: "me@x".into(),
            subject: "Hei".into(), date: 100, raw: "Hei\n".into(),
            html: String::new(), has_attachments: false,
        };
        let back = parse_mails(serialize_mails(vec![m.clone()]));
        assert_eq!(back, vec![m]);
    }

    #[test]
    fn opening_writes_nothing_and_marking_writes_a_state() {
        // The phone's own file starts empty and stays empty until asked.
        let mine: Vec<ReadMark> = Vec::new();
        assert!(!is_mail_read(mine.clone(), "a@x".into()));
        let mine = set_read_mark(mine, "a@x".into(), true, 200);
        assert!(is_mail_read(mine.clone(), "a@x".into()));
        // And the laptop's file merges in on top.
        let merged = merge_read_marks(vec![
            serialize_read_marks(mine),
            r#"{"a@x": {"read": false, "ts": 300}}"#.into(),
        ]);
        assert!(!is_mail_read(merged, "a@x".into()), "the newer state wins");
    }

    #[test]
    fn a_body_decodes_through_the_shared_crate() {
        let out = mail_body_text("Hei =C3=A5 =\nder\n".into(), String::new());
        assert!(out.contains("Hei å der"), "got {:?}", out);
    }
}
