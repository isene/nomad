// Claude from the phone, the way `claude -p` serves the laptop tools.
//
// The Messages API over plain HTTPS with the user's own API key. This
// module writes each request and reads the streamed answer; Kotlin only
// moves the bytes. A `Chat` keeps the whole conversation, so follow-up
// questions see what came before.
//
// Every request asks for the server-side fallback: when a safety check
// declines a question, the API answers it with the model Anthropic
// recommends instead of returning the refusal.

use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

pub const MODEL: &str = "claude-opus-5";
const URL: &str = "https://api.anthropic.com/v1/messages";
const MAX_TOKENS: u32 = 16000;
/// About 100,000 tokens, or 50 cents of Opus input for a first question.
/// Follow-ups read the page from the prompt cache at a tenth of that.
const PAGE_CHARS: usize = 400_000;

#[derive(Debug, uniffi::Record)]
pub struct HttpHeader {
    pub name: String,
    pub value: String,
}

#[uniffi::export]
pub fn claude_url() -> String {
    URL.into()
}

#[uniffi::export]
pub fn claude_headers(key: String) -> Vec<HttpHeader> {
    [
        ("x-api-key", key.trim()),
        ("anthropic-version", "2023-06-01"),
        ("anthropic-beta", "server-side-fallback-2026-07-01"),
        ("content-type", "application/json"),
        ("accept", "text/event-stream"),
    ]
    .into_iter()
    .map(|(n, v)| HttpHeader { name: n.into(), value: v.into() })
    .collect()
}

/// A plain message for an HTTP error: the API's own words, with the
/// usual causes said plainly.
#[uniffi::export]
pub fn claude_error_text(status: u16, body: String) -> String {
    let said = serde_json::from_str::<Value>(&body)
        .ok()
        .and_then(|v| v["error"]["message"].as_str().map(String::from))
        .unwrap_or_default();
    let plain = match status {
        401 => "The API key was refused. Check it in the settings.",
        403 => "This API key may not use Claude.",
        429 => "Too many questions at once. Try again in a minute.",
        529 => "Claude is busy. Try again in a moment.",
        s if s >= 500 => "Claude's side failed. Try again.",
        _ => "",
    };
    match (plain.is_empty(), said.is_empty()) {
        (false, true) => plain.into(),
        (false, false) => format!("{plain} ({said})"),
        (true, false) => said,
        (true, true) => format!("The request failed with HTTP {status}."),
    }
}

/// How a streamed answer ended.
#[derive(Debug, PartialEq, uniffi::Record)]
pub struct Answer {
    /// The answer's text, all of it.
    pub text: String,
    /// Empty when all went well; else what to tell the user.
    pub error: String,
    /// True when the answer stopped at the length limit.
    pub cut: bool,
}

struct State {
    turns: Vec<Value>,
    /// The content blocks of the answer being streamed, by index.
    blocks: Vec<Value>,
    stop: String,
    error: String,
}

/// One conversation with Claude.
#[derive(uniffi::Object)]
pub struct Chat {
    system: String,
    cut: bool,
    state: Mutex<State>,
}

#[uniffi::export]
impl Chat {
    /// A conversation with a system prompt of your own.
    #[uniffi::constructor]
    pub fn new(system: String) -> Arc<Self> {
        Arc::new(Chat { system, cut: false, state: Mutex::new(State::empty()) })
    }

    /// A conversation about a web page. A page longer than 400,000
    /// characters is cut there; `page_was_cut` says so.
    #[uniffi::constructor]
    pub fn for_page(title: String, url: String, text: String) -> Arc<Self> {
        let cut = text.chars().count() > PAGE_CHARS;
        let text: String = if cut { text.chars().take(PAGE_CHARS).collect() } else { text };
        let note = if cut { "\n(The page goes on; this is its first part.)" } else { "" };
        let system = format!(
            "The user is reading a web page in their phone's browser and wants to talk about it. \
             Answer briefly and plainly, in the language they write in. The screen is small, so \
             keep formatting light.\n\nTitle: {title}\nURL: {url}\n\n<page>\n{text}\n</page>{note}"
        );
        Arc::new(Chat { system, cut, state: Mutex::new(State::empty()) })
    }

    pub fn page_was_cut(&self) -> bool {
        self.cut
    }

    /// Add a question and give the request body that asks it.
    pub fn ask(&self, question: String) -> String {
        let mut s = self.state.lock().unwrap();
        s.turns.push(json!({"role": "user", "content": question}));
        s.blocks.clear();
        s.stop.clear();
        s.error.clear();
        json!({
            "model": MODEL,
            "max_tokens": MAX_TOKENS,
            "stream": true,
            "fallbacks": "default",
            "thinking": {"type": "adaptive"},
            "output_config": {"effort": "medium"},
            "system": [{"type": "text", "text": self.system, "cache_control": {"type": "ephemeral"}}],
            "messages": s.turns,
        })
        .to_string()
    }

    /// Read one line of the event stream. Gives the new text it carries,
    /// if any, to show as it arrives.
    pub fn feed(&self, line: String) -> Option<String> {
        let data = line.strip_prefix("data:")?.trim();
        let ev: Value = serde_json::from_str(data).ok()?;
        let mut s = self.state.lock().unwrap();
        match ev["type"].as_str()? {
            "content_block_start" => {
                let i = ev["index"].as_u64()? as usize;
                if s.blocks.len() <= i {
                    s.blocks.resize(i + 1, Value::Null);
                }
                s.blocks[i] = ev["content_block"].clone();
                s.blocks[i]["text"].as_str().filter(|t| !t.is_empty()).map(String::from)
            }
            "content_block_delta" => {
                let i = ev["index"].as_u64()? as usize;
                let block = s.blocks.get_mut(i)?;
                let d = &ev["delta"];
                let (field, piece) = match d["type"].as_str()? {
                    "text_delta" => ("text", d["text"].as_str()?),
                    "thinking_delta" => ("thinking", d["thinking"].as_str()?),
                    "signature_delta" => {
                        block["signature"] = d["signature"].clone();
                        return None;
                    }
                    _ => return None,
                };
                let joined = format!("{}{}", block[field].as_str().unwrap_or(""), piece);
                block[field] = Value::String(joined);
                (field == "text").then(|| piece.to_string())
            }
            "message_delta" => {
                if let Some(r) = ev["delta"]["stop_reason"].as_str() {
                    s.stop = r.into();
                }
                None
            }
            "error" => {
                s.error = ev["error"]["message"].as_str().unwrap_or("the stream broke off").into();
                None
            }
            _ => None,
        }
    }

    /// The stream is over: keep the answer for the follow-ups, or drop the
    /// question when there was no answer, so it can be asked again.
    pub fn finish(&self) -> Answer {
        let mut s = self.state.lock().unwrap();
        let text: String = s.blocks.iter().filter(|b| b["type"] == "text").filter_map(|b| b["text"].as_str()).collect();
        let error = if !s.error.is_empty() {
            format!("Claude stopped: {}", s.error)
        } else if s.stop == "refusal" {
            "Claude declined to answer that.".into()
        } else if s.stop.is_empty() {
            "The answer broke off. Try again.".into()
        } else {
            String::new()
        };
        if error.is_empty() {
            let content = echo(&s.blocks);
            s.turns.push(json!({"role": "assistant", "content": content}));
        } else {
            s.turns.pop();
        }
        let cut = s.stop == "max_tokens";
        Answer { text: if error.is_empty() { text } else { String::new() }, error, cut }
    }

    /// The request failed before any answer: drop the question.
    pub fn fail(&self) {
        self.state.lock().unwrap().turns.pop();
    }
}

impl State {
    fn empty() -> State {
        State { turns: Vec::new(), blocks: Vec::new(), stop: String::new(), error: String::new() }
    }
}

/// The answer's blocks as the next request must carry them: unchanged,
/// except that when the answer moved to a fallback model partway, the
/// first model's thinking before the switch is left out.
fn echo(blocks: &[Value]) -> Vec<Value> {
    let last_fallback = blocks.iter().rposition(|b| b["type"] == "fallback");
    blocks
        .iter()
        .enumerate()
        .filter(|(_, b)| !b.is_null())
        .filter(|(i, b)| match last_fallback {
            Some(f) if *i < f => !matches!(b["type"].as_str(), Some("thinking" | "redacted_thinking" | "tool_use")),
            _ => true,
        })
        .map(|(_, b)| b.clone())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stream(chat: &Chat, events: &[&str]) -> String {
        events.iter().filter_map(|e| chat.feed(format!("data: {e}"))).collect()
    }

    #[test]
    fn a_streamed_answer_is_shown_and_kept_for_the_follow_up() {
        let chat = Chat::for_page("T".into(), "https://a.no/".into(), "Hello page".into());
        let body: Value = serde_json::from_str(&chat.ask("What is it?".into())).unwrap();
        assert_eq!(body["model"], MODEL);
        assert_eq!(body["fallbacks"], "default");
        assert_eq!(body["stream"], true);
        assert!(body["system"][0]["text"].as_str().unwrap().contains("Hello page"));
        assert_eq!(body["system"][0]["cache_control"]["type"], "ephemeral");
        let shown = stream(&chat, &[
            r#"{"type":"message_start","message":{"model":"claude-opus-5"}}"#,
            r#"{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"#,
            r#"{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}"#,
            r#"{"type":"content_block_stop","index":0}"#,
            r#"{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}"#,
            r#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"A greeting"}}"#,
            r#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"."}}"#,
            r#"{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"#,
            r#"{"type":"message_stop"}"#,
        ]);
        assert_eq!(shown, "A greeting.");
        assert_eq!(chat.finish(), Answer { text: "A greeting.".into(), error: String::new(), cut: false });
        let next: Value = serde_json::from_str(&chat.ask("And?".into())).unwrap();
        let msgs = next["messages"].as_array().unwrap();
        assert_eq!(msgs.len(), 3);
        assert_eq!(msgs[1]["content"][0]["signature"], "sig", "thinking goes back unchanged");
        assert_eq!(msgs[1]["content"][1]["text"], "A greeting.");
    }

    #[test]
    fn a_refusal_or_a_broken_stream_drops_the_question() {
        let chat = Chat::new("sys".into());
        chat.ask("q".into());
        stream(&chat, &[r#"{"type":"message_delta","delta":{"stop_reason":"refusal"}}"#]);
        assert_eq!(chat.finish().error, "Claude declined to answer that.");
        let body: Value = serde_json::from_str(&chat.ask("again".into())).unwrap();
        assert_eq!(body["messages"].as_array().unwrap().len(), 1, "only the new question");
        stream(&chat, &[r#"{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"par"}}"#]);
        assert_eq!(chat.finish().error, "The answer broke off. Try again.");
        chat.ask("x".into());
        chat.fail();
        let body: Value = serde_json::from_str(&chat.ask("y".into())).unwrap();
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn thinking_before_a_fallback_is_left_out() {
        let blocks = vec![
            json!({"type": "thinking", "thinking": "", "signature": "a"}),
            json!({"type": "text", "text": "par"}),
            json!({"type": "fallback"}),
            json!({"type": "thinking", "thinking": "", "signature": "b"}),
            json!({"type": "text", "text": "tial"}),
        ];
        let kept: Vec<String> = echo(&blocks).iter().map(|b| b["type"].as_str().unwrap().to_string()).collect();
        assert_eq!(kept, ["text", "fallback", "thinking", "text"]);
    }

    #[test]
    fn a_long_page_is_cut_and_says_so() {
        let chat = Chat::for_page("T".into(), "u".into(), "x".repeat(PAGE_CHARS + 5));
        assert!(chat.page_was_cut());
        assert!(!Chat::for_page("T".into(), "u".into(), "short".into()).page_was_cut());
    }

    #[test]
    fn http_errors_are_said_plainly() {
        let body = r#"{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"#;
        assert_eq!(claude_error_text(401, body.into()), "The API key was refused. Check it in the settings. (invalid x-api-key)");
        assert_eq!(claude_error_text(418, "".into()), "The request failed with HTTP 418.");
        assert_eq!(claude_headers(" k ".into())[0].value, "k");
    }
}

/// Against the real API, in three steps (costs a cent):
///   CLAUDE_BODY=/tmp/b.json cargo test -p fe2o3-mobile-core --lib -- --ignored live_request
///   curl -N https://api.anthropic.com/v1/messages -H ... --data @/tmp/b.json > /tmp/s.txt
///   CLAUDE_STREAM=/tmp/s.txt cargo test -p fe2o3-mobile-core --lib -- --ignored live_request
#[cfg(test)]
mod live {
    use super::*;

    #[test]
    #[ignore]
    fn live_request() {
        let chat = Chat::for_page("Test".into(), "https://example.com/".into(), "The sky is green on Tuesdays.".into());
        let body = chat.ask("What colour is the sky on Tuesdays, in two words?".into());
        if let Ok(path) = std::env::var("CLAUDE_BODY") {
            std::fs::write(path, body).unwrap();
        }
        if let Ok(path) = std::env::var("CLAUDE_STREAM") {
            let shown: String = std::fs::read_to_string(path).unwrap().lines().filter_map(|l| chat.feed(l.into())).collect();
            let out = chat.finish();
            println!("shown: {shown:?}\noutcome: {out:?}");
            assert!(out.error.is_empty());
            assert_eq!(shown, out.text);
        }
    }
}
