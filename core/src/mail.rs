// Messages for the phone: a thin UniFFI skin over fe2o3-mail and
// fe2o3-feed.
//
// Nothing is decided here. Decoding a body, deciding what has been read,
// pulling entries out of a feed — that logic is shared with desktop
// kastrup so the two cannot drift, and this file only makes it reachable
// from Kotlin.
//
// One record covers every channel. A feed item is not mail, but it is a
// message: something with a sender, a time, a subject and a body, which
// is all the list and the widget ever ask of it.

use std::collections::HashMap;

/// One message as the phone stores and shows it, whatever channel it
/// came from. Bodies are kept raw so the decode happens on display and a
/// re-decode costs nothing if the rules improve.
#[derive(Debug, Clone, PartialEq, Default, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Message {
    /// The identity: an RFC822 Message-ID for mail (which survives
    /// maildir, IMAP and this phone, and is the key read state is agreed
    /// on), or the feed entry's own id.
    pub message_id: String,
    /// Which channel this came down. "mail" or "rss".
    ///
    /// Defaulted, like every field added after the first release: the
    /// store on disk was written before this existed, and a field serde
    /// cannot find fails the whole parse — which empties the store, and
    /// with it the read marks, the removed list and the lot.
    #[serde(default = "default_source")]
    pub source: String,
    /// Where to go to read the whole thing. Feeds only; mail is here.
    #[serde(default)]
    pub link: String,
    /// The IMAP UID, mail only. Searching the server for a Message-ID is
    /// a guess that sometimes comes back empty; a UID is the server's own
    /// handle on the message and always finds it.
    #[serde(default)]
    pub uid: u64,
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

/// Anything written before there were channels was mail.
fn default_source() -> String { "mail".to_string() }

#[uniffi::export]
pub fn parse_messages(json: String) -> Vec<Message> {
    serde_json::from_str(&json).unwrap_or_default()
}

#[uniffi::export]
pub fn serialize_messages(messages: Vec<Message>) -> String {
    serde_json::to_string(&messages).unwrap_or_else(|_| "[]".into())
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

/// Parse a feed into messages. The entry's HTML becomes the body, so
/// the same reader renders it — no second display path for a second
/// channel.
#[uniffi::export]
pub fn parse_feed(xml: String, feed_title: String, feed_url: String) -> Vec<Message> {
    feed::parse(&xml, &feed_title, &feed_url).into_iter().map(|i| Message {
        message_id: i.id,
        source: "rss".into(),
        link: i.link,
        account: i.feed_title.clone(),
        folder: i.feed_url,
        from: i.author,
        to: String::new(),
        subject: if i.title.is_empty() { "(untitled)".into() } else { i.title },
        date: i.published,
        // Nothing raw to keep: the entry arrived as HTML and that is all
        // there is. body_text falls back to it and renders it as text.
        raw: String::new(),
        html: i.html,
        has_attachments: false,
        uid: 0,
    }).collect()
}

/// Parse a Discord channel's messages into messages.
///
/// The date parser is fe2o3-feed's: Discord stamps ISO 8601 and so does
/// Atom, and a third copy of that arithmetic is a third chance to get a
/// timezone offset wrong.
///
/// Newest first, like everything else in the list.
#[uniffi::export]
pub fn parse_discord(json: String, channel_name: String, channel_id: String) -> Vec<Message> {
    let Ok(items) = serde_json::from_str::<Vec<serde_json::Value>>(&json) else {
        return Vec::new();
    };
    let mut out: Vec<Message> = items.iter().filter_map(|m| {
        let id = m.get("id")?.as_str()?;
        let content = m.get("content").and_then(|v| v.as_str()).unwrap_or("");
        let author = m.get("author");
        let name = author
            .and_then(|a| a.get("global_name").and_then(|v| v.as_str()))
            .or_else(|| author.and_then(|a| a.get("username").and_then(|v| v.as_str())))
            .unwrap_or("someone");
        let atts = m.get("attachments").and_then(|v| v.as_array())
            .map(|a| a.len()).unwrap_or(0);
        // Chat has no subject line; the message is the subject. An
        // attachment-only post would otherwise be a blank row.
        let subject = if !content.is_empty() {
            content.lines().next().unwrap_or(content).to_string()
        } else if atts > 0 {
            format!("({} attachment(s))", atts)
        } else {
            "(empty)".to_string()
        };
        Some(Message {
            message_id: format!("discord_{}", id),
            source: "discord".into(),
            link: String::new(),
            uid: 0,
            account: channel_name.clone(),
            folder: channel_id.clone(),
            from: name.to_string(),
            to: String::new(),
            subject,
            date: m.get("timestamp").and_then(|v| v.as_str())
                .and_then(feed::parse_date).unwrap_or(0),
            raw: content.to_string(),
            html: String::new(),
            has_attachments: atts > 0,
        })
    }).collect();
    out.sort_by(|a, b| b.date.cmp(&a.date));
    out
}

/// The newest id in a batch, for the next fetch to ask after. Discord
/// ids are snowflakes: bigger is later, so this is a max, not a first.
#[uniffi::export]
pub fn discord_latest_id(messages: Vec<Message>) -> String {
    messages.iter()
        .filter_map(|m| m.message_id.strip_prefix("discord_"))
        .filter_map(|s| s.parse::<u64>().ok())
        .max()
        .map(|v| v.to_string())
        .unwrap_or_default()
}

/// One attachment, without its contents.
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct MailAttachment {
    pub filename: String,
    pub mime_type: String,
    /// Decoded size in bytes.
    pub size: u64,
}

/// What a message carries, names and sizes only.
#[uniffi::export]
pub fn mail_attachments(raw: String) -> Vec<MailAttachment> {
    mail::attach::list(&raw).into_iter()
        .map(|a| MailAttachment { filename: a.filename, mime_type: a.mime_type, size: a.size })
        .collect()
}

/// The contents of one, by its index in [`mail_attachments`]. Kept
/// separate so drawing a list never marshals a 10 MB photo.
#[uniffi::export]
pub fn mail_attachment_bytes(raw: String, index: u32) -> Vec<u8> {
    mail::attach::bytes(&raw, index as usize).unwrap_or_default()
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
        let m = Message {
            message_id: "a@x".into(), source: "mail".into(), link: String::new(), uid: 0,
            account: "geir@isene.com".into(),
            folder: "INBOX".into(), from: "Someone <s@x>".into(), to: "me@x".into(),
            subject: "Hei".into(), date: 100, raw: "Hei\n".into(),
            html: String::new(), has_attachments: false,
        };
        let back = parse_messages(serialize_messages(vec![m.clone()]));
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
    fn a_store_written_before_channels_existed_still_loads() {
        // The regression that emptied a phone: source and link were added
        // without defaults, so every message already on disk failed to
        // parse and the store came back empty.
        let old = r#"[{"message_id":"a@x","account":"me@x","folder":"INBOX",
            "from":"S","to":"me@x","subject":"Hi","date":100,"raw":"Hei"}]"#;
        let back = parse_messages(old.into());
        assert_eq!(back.len(), 1, "an older store must still load");
        assert_eq!(back[0].source, "mail");
        assert_eq!(back[0].subject, "Hi");
    }

    #[test]
    fn a_discord_post_becomes_a_message() {
        let json = r#"[
          {"id":"200","content":"Second\nline two","timestamp":"2026-04-03T09:15:00.000000+00:00",
           "author":{"username":"u","global_name":"Someone"},"attachments":[]},
          {"id":"100","content":"","timestamp":"2026-04-03T08:00:00.000000+00:00",
           "author":{"username":"u2"},"attachments":[{"filename":"a.png"}]}
        ]"#;
        let msgs = parse_discord(json.into(), "#tekst".into(), "123".into());
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0].source, "discord");
        assert_eq!(msgs[0].from, "Someone", "the display name beats the handle");
        assert_eq!(msgs[0].subject, "Second", "the first line is the subject");
        assert_eq!(msgs[0].date, 1775207700);
        assert_eq!(msgs[1].from, "u2", "no display name: the handle stands in");
        assert_eq!(msgs[1].subject, "(1 attachment(s))");
        assert!(msgs[1].has_attachments);
        // Snowflakes: the max, not the first in the array.
        assert_eq!(discord_latest_id(msgs), "200");
    }

    #[test]
    fn a_feed_entry_becomes_a_message() {
        let xml = r#"<rss><channel><item><title>Post</title>
            <link>https://example.com/1</link>
            <description>&lt;p&gt;Body&lt;/p&gt;</description>
            <pubDate>Thu, 3 Apr 2026 07:15:00 +0000</pubDate></item></channel></rss>"#;
        let msgs = parse_feed(xml.into(), "Example".into(), "u".into());
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].source, "rss");
        assert_eq!(msgs[0].subject, "Post");
        assert_eq!(msgs[0].link, "https://example.com/1");
        assert_eq!(msgs[0].date, 1775200500);
        // The same reader renders it: no raw part, so the HTML is used.
        assert!(mail_body_text(msgs[0].raw.clone(), msgs[0].html.clone()).contains("Body"));
    }

    #[test]
    fn attachments_are_listed_then_fetched() {
        let raw = "Content-Type: multipart/mixed; boundary=b\r\n\r\n\
            --b\r\nContent-Type: text/plain\r\n\r\nSee attached.\r\n\
            --b\r\nContent-Disposition: attachment; filename=\"a.txt\"\r\n\
            Content-Transfer-Encoding: base64\r\n\r\naGkh\r\n--b--\r\n";
        let list = mail_attachments(raw.into());
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].filename, "a.txt");
        assert_eq!(mail_attachment_bytes(raw.into(), 0), b"hi!".to_vec());
    }

    #[test]
    fn a_body_decodes_through_the_shared_crate() {
        let out = mail_body_text("Hei =C3=A5 =\nder\n".into(), String::new());
        assert!(out.contains("Hei å der"), "got {:?}", out);
    }
}
