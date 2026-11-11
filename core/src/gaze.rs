// gaze on the phone: the parts of the laptop browser that are plain logic.
//
// The password file is the laptop's, byte for byte: "GAZE1", a 16-byte
// salt, a 12-byte nonce, then the JSON list of logins sealed with
// ChaCha20-Poly1305 under a key that Argon2id makes from the master
// password. Syncthing carries it between the two, with the bookmarks and
// the tabs sent across, in ~/.gaze/sync/ on the laptop.
//
// Kotlin owns every file. These functions take and give bytes and text.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

// ---------- addresses ----------

/// What was typed, as an address: a URL as it is, a host name with https
/// in front, anything else as a search.
#[uniffi::export]
pub fn gaze_to_uri(input: String, search: String) -> String {
    let s = input.trim();
    if s.is_empty() {
        return "about:blank".into();
    }
    if s.contains("://") || s.starts_with("about:") || s.starts_with("data:") || s.starts_with("mailto:") {
        return s.to_string();
    }
    if s == "localhost" || s.starts_with("localhost:") || s.starts_with("localhost/") {
        return format!("http://{s}");
    }
    let host = s.split(['/', '?', '#']).next().unwrap_or("");
    let host_only = host.split(':').next().unwrap_or("");
    let last = host_only.rsplit('.').next().unwrap_or("");
    let looks_like_host = !s.contains(' ')
        && host_only.contains('.')
        && !host_only.starts_with('.')
        && !host_only.ends_with('.')
        && last.len() >= 2
        && last.chars().all(|c| c.is_ascii_alphabetic());
    if looks_like_host {
        return format!("https://{s}");
    }
    search.replace("%s", &form_encode(s))
}

fn form_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// `scheme://host` of a URI, lower case, without a leading `www.`, so a
/// login saved on www.example.com fills on example.com too.
#[uniffi::export]
pub fn gaze_site_key(uri: String) -> String {
    site_key(&uri)
}

fn site_key(uri: &str) -> String {
    let (scheme, rest) = uri.trim().split_once("://").unwrap_or(("https", uri.trim()));
    let host = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host = host.rsplit('@').next().unwrap_or(host);
    let host = host.split(':').next().unwrap_or(host);
    let host = host.strip_prefix("www.").unwrap_or(host);
    format!("{}://{}", scheme.to_ascii_lowercase(), host.to_ascii_lowercase())
}

// ---------- passwords ----------
//
// The file and its rules live in the `vault` crate, which the laptop's
// gaze and the vault program use too, so the three cannot drift.

/// One entry of the password file. A web login has an `origin`; a
/// password for anything else has a `title` and no origin.
#[derive(Clone, Debug, PartialEq, Default, uniffi::Record)]
pub struct Login {
    pub origin: String,
    pub username: String,
    pub password: String,
    /// Seconds since the epoch of the last fill; the latest first.
    pub used: u64,
    #[uniffi(default = "")]
    pub title: String,
    /// The tab the entry sits under in vault. Empty for a plain web login.
    #[uniffi(default = "")]
    pub group: String,
    #[uniffi(default = "")]
    pub note: String,
    /// Seconds since the epoch of the last change.
    #[uniffi(default = 0)]
    pub changed: u64,
}

impl From<vault::Login> for Login {
    fn from(l: vault::Login) -> Login {
        Login { origin: l.origin, username: l.username, password: l.password, used: l.used, title: l.title, group: l.group, note: l.note, changed: l.changed }
    }
}

impl From<Login> for vault::Login {
    fn from(l: Login) -> vault::Login {
        vault::Login { origin: l.origin, username: l.username, password: l.password, used: l.used, title: l.title, group: l.group, note: l.note, changed: l.changed }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Enum)]
pub enum LoginChange {
    New,
    Updated,
    Same,
}

#[derive(Debug, uniffi::Record)]
pub struct Remembered {
    pub logins: Vec<Login>,
    pub change: LoginChange,
}

/// The salt a password file was sealed with, or None when it is no
/// password file.
#[uniffi::export]
pub fn gaze_vault_salt(file: Vec<u8>) -> Option<Vec<u8>> {
    vault::salt_of(&file).map(|s| s.to_vec())
}

/// A salt for a new password file.
#[uniffi::export]
pub fn gaze_vault_new_salt() -> Vec<u8> {
    vault::new_salt().to_vec()
}

/// The key a master password and a salt make. Slow on purpose (Argon2id,
/// 19 MB), so call it off the main thread.
#[uniffi::export]
pub fn gaze_vault_key(master: String, salt: Vec<u8>) -> Vec<u8> {
    vault::derive(&master, &salt).expect("a 16-byte salt and a 32-byte key are valid").to_vec()
}

/// The entries in a password file, or None when the key is wrong.
#[uniffi::export]
pub fn gaze_vault_open(file: Vec<u8>, key: Vec<u8>) -> Option<Vec<Login>> {
    let key: [u8; 32] = key.try_into().ok()?;
    Some(vault::open(&file, &key)?.into_iter().map(Login::from).collect())
}

/// The password file for `logins`, sealed under `key` with a fresh nonce.
#[uniffi::export]
pub fn gaze_vault_seal(logins: Vec<Login>, key: Vec<u8>, salt: Vec<u8>) -> Vec<u8> {
    let key: [u8; 32] = key.try_into().expect("a 32-byte key");
    let salt: [u8; vault::SALT_LEN] = salt.try_into().expect("a 16-byte salt");
    let logins: Vec<vault::Login> = logins.into_iter().map(Into::into).collect();
    vault::seal(&logins, &key, &salt).expect("sealing cannot fail")
}

/// The logins for the site of `uri`, the last used first. An entry with
/// no site (a server, a door) fills no page.
#[uniffi::export]
pub fn gaze_logins_for_site(logins: Vec<Login>, uri: String) -> Vec<Login> {
    let site = site_key(&uri);
    let mut out: Vec<Login> = logins.into_iter().filter(|l| is_web(l) && site_key(&l.origin) == site).collect();
    out.sort_by(|a, b| b.used.cmp(&a.used).then(a.username.cmp(&b.username)));
    out
}

fn is_web(l: &Login) -> bool {
    !l.origin.trim().is_empty()
}

/// What to call an entry in a list: its title, or the site.
#[uniffi::export]
pub fn gaze_login_name(login: Login) -> String {
    vault::Login::from(login).name()
}

/// Keep a login the page just sent. A known username on the site gets its
/// password replaced; the same password again changes nothing.
#[uniffi::export]
pub fn gaze_remember(logins: Vec<Login>, login: Login) -> Remembered {
    let mut logins = logins;
    let site = site_key(&login.origin);
    let change = match logins.iter_mut().find(|l| is_web(l) && site_key(&l.origin) == site && l.username == login.username) {
        Some(l) if l.password == login.password => LoginChange::Same,
        Some(l) => {
            l.password = login.password;
            l.used = now();
            l.changed = now();
            LoginChange::Updated
        }
        None => {
            logins.push(Login { used: now(), changed: now(), ..login });
            LoginChange::New
        }
    };
    Remembered { logins, change }
}

/// The logins without the one for `username` on the site of `uri`.
#[uniffi::export]
pub fn gaze_forget(logins: Vec<Login>, uri: String, username: String) -> Vec<Login> {
    let site = site_key(&uri);
    logins.into_iter().filter(|l| !(is_web(l) && site_key(&l.origin) == site && l.username == username)).collect()
}

// ---------- the vault app ----------

fn to_vault(logins: Vec<Login>) -> Vec<vault::Login> {
    logins.into_iter().map(Into::into).collect()
}

/// The tabs of the vault app: Web, Other, then a tab per tag.
#[uniffi::export]
pub fn vault_tabs(logins: Vec<Login>) -> Vec<String> {
    vault::tabs(&to_vault(logins))
}

/// The entries of a tab by name, or with letters typed, every entry
/// that has them in any field but the password.
#[uniffi::export]
pub fn vault_rows(logins: Vec<Login>, tab: String, find: String) -> Vec<Login> {
    vault::rows(&to_vault(logins), &tab, &find).into_iter().map(Login::from).collect()
}

/// The entries with `entry` put in: in the place of `old`, or as a new
/// one. None when another entry already has its name, user and tags.
#[uniffi::export]
pub fn vault_put(logins: Vec<Login>, old: Option<Login>, entry: Login) -> Option<Vec<Login>> {
    let new: vault::Login = Login { changed: now(), ..entry }.into();
    let old: Option<vault::Login> = old.map(Into::into);
    let mut all = to_vault(logins);
    let at = old.as_ref().and_then(|o| all.iter().position(|l| vault::same_entry(l, o)));
    if all.iter().enumerate().any(|(i, l)| Some(i) != at && vault::same_entry(l, &new)) { return None; }
    match at {
        Some(i) => all[i] = new,
        None => all.push(new),
    }
    Some(all.into_iter().map(Login::from).collect())
}

/// The entries without `entry`.
#[uniffi::export]
pub fn vault_drop(logins: Vec<Login>, entry: Login) -> Vec<Login> {
    let gone: vault::Login = entry.into();
    to_vault(logins).into_iter().filter(|l| !vault::same_entry(l, &gone)).map(Login::from).collect()
}

/// A new password of `len` characters.
#[uniffi::export]
pub fn vault_new_password(len: u32) -> String {
    vault::new_password(len as usize)
}

fn now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

// ---------- ad blocking ----------

/// The domains of a hosts list. A domain on the list is blocked with all
/// its subdomains, as on the laptop.
#[derive(uniffi::Object)]
pub struct AdList {
    hosts: HashSet<String>,
}

#[uniffi::export]
impl AdList {
    #[uniffi::constructor]
    pub fn new(hosts_file: String) -> Arc<Self> {
        let mut hosts = HashSet::new();
        for line in hosts_file.lines() {
            let line = line.split('#').next().unwrap_or("").trim();
            let mut parts = line.split_whitespace();
            let (Some(addr), Some(host)) = (parts.next(), parts.next()) else { continue };
            if addr != "0.0.0.0" && addr != "127.0.0.1" {
                continue;
            }
            let host = host.trim_end_matches('.').to_ascii_lowercase();
            if !host.contains('.') || host.starts_with("localhost") || host.ends_with(".local")
                || host.ends_with(".localdomain") || !host.chars().any(|c| c.is_ascii_alphabetic())
            {
                continue;
            }
            hosts.insert(host);
        }
        Arc::new(AdList { hosts })
    }

    /// True when `host` or a domain above it is on the list.
    pub fn blocks(&self, host: String) -> bool {
        let host = host.to_ascii_lowercase();
        let mut h = host.as_str();
        loop {
            if self.hosts.contains(h) {
                return true;
            }
            match h.split_once('.') {
                Some((_, rest)) if rest.contains('.') => h = rest,
                _ => return false,
            }
        }
    }

    pub fn size(&self) -> u64 {
        self.hosts.len() as u64
    }
}

// ---------- bookmarks and history ----------

/// A page to offer: a bookmark, a page from the history, or a tab sent
/// from the other machine.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Place {
    pub url: String,
    pub title: String,
    pub bookmark: bool,
}

const KEEP: usize = 5000;
const REWRITE_AT: usize = 10_000;
const MONTH: u64 = 30 * 24 * 3600;

struct Visit {
    url: String,
    title: String,
    last: u64,
    count: u32,
}

struct PlacesInner {
    bookmarks: Vec<(String, String)>,
    visits: Vec<Visit>,
    history_lines: usize,
}

/// Bookmarks (the laptop's file, one URL, a tab and a title per line) and
/// the phone's history (a time, a tab, the URL, a tab, the title per
/// visit), for the address line's suggestions.
#[derive(uniffi::Object)]
pub struct Places {
    inner: Mutex<PlacesInner>,
}

fn parse_bookmarks(text: &str) -> Vec<(String, String)> {
    text.lines()
        .filter_map(|l| {
            let (url, title) = l.split_once('\t').unwrap_or((l, ""));
            let url = url.trim();
            (!url.is_empty()).then(|| (url.to_string(), title.trim().to_string()))
        })
        .collect()
}

fn bookmarks_text(list: &[(String, String)]) -> String {
    list.iter().map(|(u, t)| format!("{u}\t{t}\n")).collect()
}

fn one_line(s: &str) -> String {
    s.replace(['\t', '\n', '\r'], " ").trim().to_string()
}

#[uniffi::export]
impl Places {
    #[uniffi::constructor]
    pub fn new(bookmarks: String, history: String) -> Arc<Self> {
        let mut by_url: HashMap<String, Visit> = HashMap::new();
        let mut lines = 0;
        for line in history.lines() {
            lines += 1;
            let mut parts = line.splitn(3, '\t');
            let (Some(when), Some(url), title) = (parts.next(), parts.next(), parts.next().unwrap_or("")) else {
                continue;
            };
            let when: u64 = when.parse().unwrap_or(0);
            match by_url.get_mut(url) {
                Some(v) => {
                    v.count += 1;
                    if when >= v.last {
                        v.last = when;
                        if !title.is_empty() {
                            v.title = title.to_string();
                        }
                    }
                }
                None => {
                    by_url.insert(url.to_string(), Visit { url: url.to_string(), title: title.to_string(), last: when, count: 1 });
                }
            }
        }
        let mut visits: Vec<Visit> = by_url.into_values().collect();
        visits.sort_by(|a, b| b.last.cmp(&a.last));
        visits.truncate(KEEP);
        Arc::new(Places {
            inner: Mutex::new(PlacesInner { bookmarks: parse_bookmarks(&bookmarks), visits, history_lines: lines }),
        })
    }

    /// Take the bookmark file again, after Syncthing brought a new one.
    pub fn set_bookmarks(&self, text: String) {
        self.inner.lock().unwrap().bookmarks = parse_bookmarks(&text);
    }

    pub fn bookmarks(&self) -> Vec<Place> {
        let p = self.inner.lock().unwrap();
        p.bookmarks.iter().map(|(u, t)| Place { url: u.clone(), title: t.clone(), bookmark: true }).collect()
    }

    pub fn is_bookmarked(&self, url: String) -> bool {
        self.inner.lock().unwrap().bookmarks.iter().any(|(u, _)| *u == url)
    }

    /// Add a bookmark, or give a known one its new title, on top of the
    /// file as it is now (the laptop may have changed it). Gives the new
    /// file.
    pub fn bookmark(&self, current: String, url: String, title: String) -> String {
        let mut p = self.inner.lock().unwrap();
        p.bookmarks = parse_bookmarks(&current);
        let (url, title) = (one_line(&url), one_line(&title));
        match p.bookmarks.iter_mut().find(|(u, _)| *u == url) {
            Some(b) => {
                if !title.is_empty() {
                    b.1 = title;
                }
            }
            None => p.bookmarks.push((url, title)),
        }
        bookmarks_text(&p.bookmarks)
    }

    /// The file as it is now, without `url`.
    pub fn unbookmark(&self, current: String, url: String) -> String {
        let mut p = self.inner.lock().unwrap();
        p.bookmarks = parse_bookmarks(&current);
        p.bookmarks.retain(|(u, _)| *u != url);
        bookmarks_text(&p.bookmarks)
    }

    /// Note a visit. Gives the line to append to the history file, or an
    /// empty string for a page not worth remembering.
    pub fn visit(&self, url: String, title: String) -> String {
        if url.is_empty() || url.starts_with("about:") || url.starts_with("data:") {
            return String::new();
        }
        let now = now();
        let title = one_line(&title);
        let mut p = self.inner.lock().unwrap();
        match p.visits.iter().position(|v| v.url == url) {
            Some(i) => {
                let mut v = p.visits.remove(i);
                v.last = now;
                v.count += 1;
                if !title.is_empty() {
                    v.title = title.clone();
                }
                p.visits.insert(0, v);
            }
            None => {
                p.visits.insert(0, Visit { url: url.clone(), title: title.clone(), last: now, count: 1 });
                p.visits.truncate(KEEP);
            }
        }
        p.history_lines += 1;
        format!("{now}\t{url}\t{title}\n")
    }

    /// The whole history folded to one line per visit (up to 50 per page),
    /// when the file has grown past ten thousand lines; else empty. Write
    /// it over the file when it is not empty.
    pub fn history_to_rewrite(&self) -> String {
        let mut p = self.inner.lock().unwrap();
        if p.history_lines <= REWRITE_AT {
            return String::new();
        }
        let mut text = String::new();
        let mut lines = 0;
        for v in p.visits.iter().rev() {
            for _ in 0..v.count.min(50) {
                text.push_str(&format!("{}\t{}\t{}\n", v.last, v.url, v.title));
                lines += 1;
            }
        }
        p.history_lines = lines;
        text
    }

    /// For the start page: the pages visited most among those seen in the
    /// last month. Bookmarks are left out, as they have a list of their own.
    pub fn top(&self, limit: u32) -> Vec<Place> {
        let p = self.inner.lock().unwrap();
        let since = now().saturating_sub(MONTH);
        let mut out: Vec<&Visit> =
            p.visits.iter().filter(|v| v.last >= since && !p.bookmarks.iter().any(|(u, _)| *u == v.url)).collect();
        out.sort_by(|a, b| b.count.cmp(&a.count).then(b.last.cmp(&a.last)));
        out.into_iter()
            .take(limit as usize)
            .map(|v| Place { url: v.url.clone(), title: v.title.clone(), bookmark: false })
            .collect()
    }

    /// The bookmarks visited most, most first. Those never visited keep
    /// the order of the file.
    pub fn most_used(&self, limit: u32) -> Vec<Place> {
        let p = self.inner.lock().unwrap();
        let counts: HashMap<&str, u32> = p.visits.iter().map(|v| (v.url.as_str(), v.count)).collect();
        let mut out: Vec<&(String, String)> = p.bookmarks.iter().collect();
        out.sort_by_key(|b| std::cmp::Reverse(counts.get(b.0.as_str()).copied().unwrap_or(0)));
        out.into_iter()
            .take(limit as usize)
            .map(|(u, t)| Place { url: u.clone(), title: t.clone(), bookmark: true })
            .collect()
    }

    /// What to offer for `query`: every word must appear in the URL or the
    /// title. Bookmarks come first, then the most visited. An empty query
    /// gives the latest pages.
    pub fn suggest(&self, query: String, limit: u32) -> Vec<Place> {
        let p = self.inner.lock().unwrap();
        let words: Vec<String> = query.split_whitespace().map(|w| w.to_lowercase()).collect();
        let hit = |url: &str, title: &str| {
            let (u, t) = (url.to_lowercase(), title.to_lowercase());
            words.iter().all(|w| u.contains(w.as_str()) || t.contains(w.as_str()))
        };
        let mut out: Vec<(i64, Place)> = Vec::new();
        if !words.is_empty() {
            for (url, title) in &p.bookmarks {
                if hit(url, title) {
                    let count = p.visits.iter().find(|v| v.url == *url).map(|v| v.count).unwrap_or(0) as i64;
                    out.push((1_000_000 + count, Place { url: url.clone(), title: title.clone(), bookmark: true }));
                }
            }
        }
        for (rank, v) in p.visits.iter().enumerate() {
            if p.bookmarks.iter().any(|(u, _)| *u == v.url) && !words.is_empty() || !hit(&v.url, &v.title) {
                continue;
            }
            let score = if words.is_empty() { -(rank as i64) } else { v.count as i64 * 100 - rank as i64 };
            let bookmark = words.is_empty() && p.bookmarks.iter().any(|(u, _)| *u == v.url);
            out.push((score, Place { url: v.url.clone(), title: v.title.clone(), bookmark }));
        }
        out.sort_by(|a, b| b.0.cmp(&a.0));
        out.into_iter().take(limit as usize).map(|(_, c)| c).collect()
    }
}

// ---------- tabs sent between the phone and the laptop ----------

/// A tab to send: one file with the URL, a tab and the title.
#[uniffi::export]
pub fn gaze_tab_text(url: String, title: String) -> String {
    format!("{}\t{}\n", one_line(&url), one_line(&title))
}

/// The tab in a file the other machine sent, or None when it holds none.
#[uniffi::export]
pub fn gaze_tab_parse(text: String) -> Option<Place> {
    let (url, title) = parse_bookmarks(&text).into_iter().next()?;
    Some(Place { url, title, bookmark: false })
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: &str = "https://duckduckgo.com/?q=%s";

    #[test]
    fn typed_text_becomes_an_address_or_a_search() {
        let u = |s: &str| gaze_to_uri(s.into(), S.into());
        assert_eq!(u("isene.org"), "https://isene.org");
        assert_eq!(u("https://a.no/x"), "https://a.no/x");
        assert_eq!(u("localhost:8080"), "http://localhost:8080");
        assert_eq!(u("free will"), "https://duckduckgo.com/?q=free+will");
        assert_eq!(u("v1.2"), "https://duckduckgo.com/?q=v1.2");
        assert_eq!(site_key("https://www.Example.com:443/a?b#c"), "https://example.com");
    }

    /// A file sealed by the laptop's gaze (its own code, copied here) opens
    /// on the phone, and one the phone seals opens there.
    #[test]
    fn the_password_file_is_the_laptops() {
        let salt = gaze_vault_new_salt();
        let key = gaze_vault_key("hunter2".into(), salt.clone());
        let login = Login { origin: "https://www.example.com".into(), username: "alice".into(), password: "pw".into(), used: 7, ..Login::default() };
        let file = gaze_vault_seal(vec![login.clone()], key.clone(), salt.clone());
        assert_eq!(&file[..5], b"GAZE1");
        assert_eq!(gaze_vault_salt(file.clone()).unwrap(), salt);
        assert_eq!(gaze_vault_open(file.clone(), key).unwrap(), vec![login]);
        let wrong = gaze_vault_key("nope".into(), salt);
        assert!(gaze_vault_open(file, wrong).is_none(), "a wrong master password opens nothing");
        assert!(gaze_vault_salt(b"not a gaze file at all, no".to_vec()).is_none());
    }

    /// An entry with no site keeps every field through a save, makes the
    /// file the newer kind, and fills no page.
    #[test]
    fn an_entry_without_a_site_is_kept_and_fills_nothing() {
        let salt = gaze_vault_new_salt();
        let key = gaze_vault_key("hunter2".into(), salt.clone());
        let door = Login { title: "Office door".into(), group: "Work".into(), note: "side entrance".into(), password: "1234".into(), changed: 9, ..Login::default() };
        let web = Login { origin: "https://a.no".into(), username: "u".into(), password: "pw".into(), ..Login::default() };
        let file = gaze_vault_seal(vec![door.clone(), web.clone()], key.clone(), salt);
        assert_eq!(&file[..5], b"GAZE2");
        let back = gaze_vault_open(file, key).unwrap();
        assert_eq!(back, vec![door.clone(), web.clone()]);
        assert_eq!(gaze_logins_for_site(back.clone(), "https://a.no/login".into()), vec![web.clone()]);
        assert!(gaze_logins_for_site(back.clone(), "".into()).is_empty());
        assert_eq!(gaze_login_name(door), "Office door");
    }

    #[test]
    fn the_vault_app_lists_changes_and_drops_entries() {
        let door = Login { title: "Office door".into(), group: "work keys".into(), password: "1234".into(), ..Login::default() };
        let web = Login { origin: "https://a.no".into(), username: "u".into(), password: "pw".into(), ..Login::default() };
        let all = vec![door.clone(), web.clone()];
        assert_eq!(vault_tabs(all.clone()), ["Web", "Other", "keys", "work"]);
        assert_eq!(vault_rows(all.clone(), "work".into(), "".into()), vec![door.clone()]);
        assert_eq!(vault_rows(all.clone(), "Web".into(), "office".into()), vec![door.clone()], "typing looks in every tab");
        // A changed password lands on the same entry.
        let changed = vault_put(all.clone(), Some(door.clone()), Login { password: "9999".into(), ..door.clone() }).unwrap();
        assert_eq!(changed.len(), 2);
        assert_eq!(changed[0].password, "9999");
        assert!(changed[0].changed > 0);
        // A second entry of the same name, user and tags is refused.
        assert!(vault_put(all.clone(), None, door.clone()).is_none());
        assert_eq!(vault_put(all.clone(), None, Login { title: "Bike".into(), ..Login::default() }).unwrap().len(), 3);
        assert_eq!(vault_drop(all, door), vec![web]);
        assert_eq!(vault_new_password(24).chars().count(), 24);
    }

    #[test]
    fn a_login_is_new_then_the_same_then_updated_then_forgotten() {
        let l = |pw: &str| Login { origin: "https://a.no".into(), username: "u".into(), password: pw.into(), used: 0, ..Login::default() };
        let r = gaze_remember(vec![], l("one"));
        assert_eq!(r.change, LoginChange::New);
        let r = gaze_remember(r.logins, l("one"));
        assert_eq!(r.change, LoginChange::Same);
        let r = gaze_remember(r.logins, l("two"));
        assert_eq!(r.change, LoginChange::Updated);
        assert_eq!(r.logins.len(), 1);
        assert_eq!(gaze_logins_for_site(r.logins.clone(), "https://www.a.no/login".into())[0].password, "two");
        assert!(gaze_forget(r.logins, "https://a.no/x".into(), "u".into()).is_empty());
    }

    #[test]
    fn a_listed_domain_is_blocked_with_its_subdomains() {
        let ads = AdList::new("# head\n127.0.0.1 localhost\n0.0.0.0 0.0.0.0\n0.0.0.0 Ads.Example.com # c\n0.0.0.0 tracker.net\n".into());
        assert_eq!(ads.size(), 2);
        assert!(ads.blocks("ads.example.com".into()));
        assert!(ads.blocks("x.y.tracker.net".into()));
        assert!(!ads.blocks("example.com".into()), "the domain above a listed one is not blocked");
        assert!(!ads.blocks("net".into()));
        assert!(!ads.blocks("isene.org".into()));
    }

    #[test]
    fn suggestions_put_bookmarks_first_and_every_word_must_match() {
        let places = Places::new(
            "https://isene.org/\tGeir Isene\nhttps://a.no/\tA page\n".into(),
            "100\thttps://b.no/rust\tRust news\n200\thttps://c.no/\tIsene again\n300\thttps://b.no/rust\tRust news\n".into(),
        );
        let s = places.suggest("isene".into(), 10);
        assert_eq!(s[0].url, "https://isene.org/");
        assert!(s[0].bookmark);
        assert_eq!(s[1].url, "https://c.no/");
        assert!(places.suggest("rust news".into(), 10).iter().all(|p| p.url == "https://b.no/rust"));
        let latest = places.suggest("".into(), 10);
        assert_eq!(latest[0].url, "https://b.no/rust", "nothing typed: the latest page first");
        assert_eq!(places.visit("about:blank".into(), "".into()), "");
        assert!(places.visit("https://d.no/".into(), "D\tpage".into()).ends_with("\thttps://d.no/\tD page\n"));
        assert_eq!(places.suggest("".into(), 1)[0].url, "https://d.no/");
    }

    #[test]
    fn the_start_page_lists_the_most_visited_and_the_most_used_bookmarks() {
        let t = now();
        let history = format!(
            "{t}\thttps://b.no/\tB\n{t}\thttps://c.no/\tC\n{t}\thttps://c.no/\tC\n{t}\thttps://a.no/\tA\n100\thttps://old.no/\tOld\n"
        );
        let places = Places::new("https://never.no/\tNever\nhttps://a.no/\tA\n".into(), history);
        let top: Vec<String> = places.top(10).into_iter().map(|p| p.url).collect();
        assert_eq!(top, ["https://c.no/", "https://b.no/"], "most visited first, no bookmark, nothing old");
        let marks = places.most_used(10);
        assert_eq!(marks[0].url, "https://a.no/");
        assert!(marks.iter().all(|p| p.bookmark));
        assert_eq!(places.most_used(1).len(), 1);
    }

    #[test]
    fn a_bookmark_lands_on_top_of_the_file_as_it_is_now() {
        let places = Places::new("https://a.no/\tA\n".into(), String::new());
        // The laptop added b.no since the phone read the file.
        let file = places.bookmark("https://a.no/\tA\nhttps://b.no/\tB\n".into(), "https://c.no/".into(), "C".into());
        assert_eq!(file, "https://a.no/\tA\nhttps://b.no/\tB\nhttps://c.no/\tC\n");
        assert!(places.is_bookmarked("https://b.no/".into()));
        let file = places.unbookmark(file, "https://a.no/".into());
        assert_eq!(file, "https://b.no/\tB\nhttps://c.no/\tC\n");
    }

    #[test]
    fn a_sent_tab_reads_back() {
        let text = gaze_tab_text("https://isene.org/".into(), "Geir\tIsene".into());
        assert_eq!(text, "https://isene.org/\tGeir Isene\n");
        let tab = gaze_tab_parse(text).unwrap();
        assert_eq!((tab.url.as_str(), tab.title.as_str()), ("https://isene.org/", "Geir Isene"));
        assert!(gaze_tab_parse("\n".into()).is_none());
    }
}
