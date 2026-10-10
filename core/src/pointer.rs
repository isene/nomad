// pointer: a file manager's hands. Listing, sorting and searching, and the
// work on files: copy, move, rename, trash, restore, undo.
//
// This is the one module of the crate that touches files. A file manager's
// logic is its file handling, and written here the host tests run the same
// code that moves the user's files on the phone.
//
// Three promises, all tested below:
//   - Nothing is overwritten. A copy or move that meets a taken name gets
//     a new one: "notes (2).txt".
//   - Nothing is deleted outright, except by "empty the trash". A delete
//     moves the item to a trash folder on the same volume.
//   - A copy that fails or is cancelled leaves no half file behind.
// And no panics: the release profile aborts on one.
//
// A zip or tar file is walked like a folder, and read only: see "archives
// as folders" below.

use std::cmp::Ordering;
use std::collections::hash_map::DefaultHasher;
use std::collections::{HashSet, VecDeque};
use std::fs::{self, File};
use std::hash::{Hash, Hasher};
use std::io::{self, BufReader, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering as Atomic};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const TRASH: &str = ".pointer-trash";

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, uniffi::Enum)]
pub enum Kind {
    Dir,
    Image,
    Video,
    Audio,
    Text,
    Pdf,
    Archive,
    Apk,
    Other,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Entry {
    pub name: String,
    pub path: String,
    pub kind: Kind,
    /// Bytes; 0 for a folder.
    pub size: u64,
    /// "2.4 MB"; empty for a folder.
    pub size_text: String,
    /// Seconds since 1970; 0 when it is not known.
    pub modified: i64,
    pub hidden: bool,
    /// For a hit of a search inside files: the number of the line the words
    /// stand on, from 1. Else 0.
    pub line: u32,
    /// The text of that line; else empty.
    pub note: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SortBy {
    Name,
    /// Largest first.
    Size,
    /// Newest first.
    Time,
    Kind,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Listing {
    pub entries: Vec<Entry>,
    /// Why the folder could not be read; empty when it could.
    pub error: String,
    /// True when the folder is an archive, or inside one.
    pub packed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UndoKind {
    /// `to` is the new copy.
    Copied,
    /// The item went from `from` to `to`.
    Moved,
    Renamed,
    /// `from` is where it was, `to` its folder in the trash.
    Trashed,
    /// `to` is the new folder.
    Made,
}

/// How to take one step back.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Undo {
    pub kind: UndoKind,
    pub from: String,
    pub to: String,
}

/// What came of one step.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Done {
    /// Empty when it went well.
    pub error: String,
    /// How to take it back; None when nothing was done.
    pub undo: Option<Undo>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Trashed {
    /// The item's folder in the trash; hand it to `pointer_restore`.
    pub id: String,
    pub name: String,
    /// Where it was.
    pub from: String,
    /// When it was trashed, seconds since 1970.
    pub time: i64,
    pub kind: Kind,
    pub size_text: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TreeSize {
    pub bytes: u64,
    pub files: u64,
    /// "1.2 GB in 340 files"
    pub text: String,
}

/// A named place: a bookmark, or one step of the path on top of the screen.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Mark {
    pub name: String,
    pub path: String,
}

// ---------- names, kinds, sizes ----------

fn text_of(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

fn name_of(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// The part after the last dot, in lower case; none for ".bashrc".
fn extension(name: &str) -> &str {
    match name.rfind('.') {
        Some(i) if i > 0 => name.get(i + 1..).unwrap_or(""),
        _ => "",
    }
}

fn kind_of(name: &str, is_dir: bool) -> Kind {
    if is_dir {
        return Kind::Dir;
    }
    match extension(name).to_ascii_lowercase().as_str() {
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "bmp" | "heic" | "heif" | "avif" | "svg" => Kind::Image,
        "mp4" | "mkv" | "webm" | "mov" | "avi" | "3gp" | "m4v" => Kind::Video,
        "mp3" | "ogg" | "opus" | "flac" | "wav" | "m4a" | "aac" | "wma" => Kind::Audio,
        "txt" | "md" | "hl" | "log" | "json" | "xml" | "html" | "htm" | "css" | "js" | "ts" | "rs" | "py"
        | "rb" | "sh" | "c" | "h" | "cpp" | "java" | "kt" | "kts" | "toml" | "yaml" | "yml" | "ini"
        | "conf" | "cfg" | "csv" | "tex" | "srt" | "ics" | "vcf" | "eml" | "gradle" | "properties" => Kind::Text,
        "pdf" => Kind::Pdf,
        "zip" | "tar" | "gz" | "tgz" | "bz2" | "xz" | "7z" | "rar" | "zst" | "jar" => Kind::Archive,
        "apk" => Kind::Apk,
        _ => Kind::Other,
    }
}

/// "0 B", "999 B", "1.4 KB", "12 MB".
#[uniffi::export]
pub fn pointer_size_text(bytes: u64) -> String {
    size_text(bytes)
}

fn size_text(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{} B", bytes)
    } else if value < 10.0 {
        format!("{:.1} {}", value, UNITS[unit])
    } else {
        format!("{:.0} {}", value, UNITS[unit])
    }
}

/// Names the way people order them: "img2" before "img10", case ignored.
fn natural_cmp(a: &str, b: &str) -> Ordering {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let (mut i, mut j) = (0, 0);
    while i < a.len() && j < b.len() {
        if a[i].is_ascii_digit() && b[j].is_ascii_digit() {
            let run = |s: &[u8], mut k: usize| {
                let start = k;
                while k < s.len() && s[k].is_ascii_digit() {
                    k += 1;
                }
                (start, k)
            };
            let ((sa, ea), (sb, eb)) = (run(a, i), run(b, j));
            let strip = |s: &[u8], from: usize, to: usize| {
                let mut k = from;
                while k + 1 < to && s[k] == b'0' {
                    k += 1;
                }
                k
            };
            let (na, nb) = (&a[strip(a, sa, ea)..ea], &b[strip(b, sb, eb)..eb]);
            let order = na.len().cmp(&nb.len()).then_with(|| na.cmp(nb));
            if order != Ordering::Equal {
                return order;
            }
            i = ea;
            j = eb;
        } else {
            let order = a[i].to_ascii_lowercase().cmp(&b[j].to_ascii_lowercase());
            if order != Ordering::Equal {
                return order;
            }
            i += 1;
            j += 1;
        }
    }
    (a.len() - i).cmp(&(b.len() - j))
}

fn epoch(t: io::Result<SystemTime>) -> i64 {
    t.ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_secs() as i64)
}

/// One item with its facts. A link is followed; a broken one still shows.
fn entry_of(path: &Path) -> Option<Entry> {
    let meta = fs::metadata(path).or_else(|_| fs::symlink_metadata(path)).ok()?;
    let name = name_of(path);
    let is_dir = meta.is_dir();
    let size = if is_dir { 0 } else { meta.len() };
    Some(Entry {
        hidden: name.starts_with('.'),
        kind: kind_of(&name, is_dir),
        path: text_of(path),
        size,
        size_text: if is_dir { String::new() } else { size_text(size) },
        modified: epoch(meta.modified()),
        line: 0,
        note: String::new(),
        name,
    })
}

// ---------- listing ----------

/// The items of a folder: folders first, then files, in the asked order.
/// The trash folder is never among them.
#[uniffi::export]
pub fn pointer_list(dir: String, sort: SortBy, reverse: bool, hidden: bool) -> Listing {
    let read = match fs::read_dir(&dir) {
        Ok(r) => r,
        Err(e) => {
            // Not a folder on the disk: it may be one inside an archive.
            return match packed(&dir) {
                Some((file, inner)) => list_packed(&file, &inner, sort, reverse, hidden),
                None => Listing { entries: Vec::new(), error: plain(&e), packed: false },
            };
        }
    };
    let mut entries: Vec<Entry> = read
        .flatten()
        .filter_map(|d| entry_of(&d.path()))
        .filter(|e| (hidden || !e.hidden) && e.name != TRASH)
        .collect();
    sort_entries(&mut entries, sort, reverse);
    Listing { entries, error: String::new(), packed: false }
}

/// Folders first, then files, in the asked order.
fn sort_entries(entries: &mut [Entry], sort: SortBy, reverse: bool) {
    entries.sort_by(|a, b| {
        let dirs = (b.kind == Kind::Dir).cmp(&(a.kind == Kind::Dir));
        let by_name = natural_cmp(&a.name, &b.name);
        let order = match sort {
            SortBy::Name => by_name,
            SortBy::Size => b.size.cmp(&a.size).then(by_name),
            SortBy::Time => b.modified.cmp(&a.modified).then(by_name),
            SortBy::Kind => a
                .kind
                .cmp(&b.kind)
                .then_with(|| extension(&a.name).to_lowercase().cmp(&extension(&b.name).to_lowercase()))
                .then(by_name),
        };
        dirs.then(if reverse { order.reverse() } else { order })
    });
}

/// The path as steps from its volume's root, for the line on top of the
/// screen. Empty when the path is on no known volume.
#[uniffi::export]
pub fn pointer_crumbs(path: String, roots: Vec<String>) -> Vec<Mark> {
    let Some(root) = root_of(&path, &roots) else { return Vec::new() };
    let mut out = vec![Mark { name: name_of(Path::new(&root)), path: root.clone() }];
    let mut at = PathBuf::from(&root);
    for part in path.get(root.len()..).unwrap_or("").split('/').filter(|p| !p.is_empty()) {
        at.push(part);
        out.push(Mark { name: part.to_string(), path: text_of(&at) });
    }
    out
}

/// The volume a path is on: the longest root it lies under.
fn root_of(path: &str, roots: &[String]) -> Option<String> {
    roots
        .iter()
        .map(|r| r.trim_end_matches('/'))
        .filter(|r| !r.is_empty() && (path == *r || path.starts_with(&format!("{}/", r))))
        .max_by_key(|r| r.len())
        .map(str::to_string)
}

// ---------- the running job ----------

// One batch of copies or moves runs at a time. The shell resets these
// before it starts one, reads the bytes for its progress bar, and sets the
// flag when the user cancels.
static CANCEL: AtomicBool = AtomicBool::new(false);
static BYTES: AtomicU64 = AtomicU64::new(0);

#[uniffi::export]
pub fn pointer_job_start() {
    CANCEL.store(false, Atomic::Relaxed);
    BYTES.store(0, Atomic::Relaxed);
}

#[uniffi::export]
pub fn pointer_job_cancel() {
    CANCEL.store(true, Atomic::Relaxed);
}

/// Bytes copied since the job started.
#[uniffi::export]
pub fn pointer_job_bytes() -> u64 {
    BYTES.load(Atomic::Relaxed)
}

// ---------- copy, move, rename, new folder ----------

/// An error in words a person can read.
fn plain(e: &io::Error) -> String {
    match e.kind() {
        io::ErrorKind::NotFound => "It is not there any more.".into(),
        io::ErrorKind::PermissionDenied => "No permission.".into(),
        io::ErrorKind::AlreadyExists => "That name is taken.".into(),
        io::ErrorKind::Interrupted => "Cancelled.".into(),
        _ => e.to_string(),
    }
}

fn done(r: Result<Undo, String>) -> Done {
    match r {
        Ok(undo) => Done { error: String::new(), undo: Some(undo) },
        Err(error) => Done { error, undo: None },
    }
}

fn exists(p: &Path) -> bool {
    fs::symlink_metadata(p).is_ok()
}

/// A name in `dir` that nothing has yet: "notes.txt", else "notes (2).txt".
fn free_name(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !exists(&first) {
        return first;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 && !first.is_dir() => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    for n in 2..10_000 {
        let next = dir.join(format!("{} ({}){}", stem, n, ext));
        if !exists(&next) {
            return next;
        }
    }
    dir.join(format!("{} ({}){}", stem, epoch(Ok(SystemTime::now())), ext))
}

/// A name a person typed: one path step, nothing more.
fn good_name(name: &str) -> Result<&str, String> {
    let name = name.trim();
    if name.is_empty() || name == "." || name == ".." || name.contains('/') || name.contains('\0') {
        return Err("That is not a name a file can have.".into());
    }
    Ok(name)
}

/// How a copy is made: the buffer the bytes pass through, and whether each
/// file must be on the disk, not only on its way there, before it counts.
struct Copier {
    buf: Vec<u8>,
    sync: bool,
}

impl Copier {
    fn new(sync: bool) -> Self {
        Copier { buf: vec![0u8; 1 << 20], sync }
    }
}

/// One file, with the time it was changed.
fn copy_file(src: &Path, dest: &Path, how: &mut Copier) -> io::Result<()> {
    let mut from = File::open(src)?;
    let time = from.metadata().and_then(|m| m.modified()).ok();
    write_file(&mut from, dest, time, how)
}

/// Bytes into a new file, written under a hidden name first and given its
/// real name only when all of it is there.
fn write_file(from: &mut dyn Read, dest: &Path, time: Option<SystemTime>, how: &mut Copier) -> io::Result<()> {
    let part = dest.with_file_name(format!(".{}.part", name_of(dest)));
    let mut run = || -> io::Result<()> {
        let mut to = File::create(&part)?;
        let buf = &mut how.buf[..];
        loop {
            if CANCEL.load(Atomic::Relaxed) {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            let n = match from.read(buf) {
                Ok(0) => break,
                Ok(n) => n,
                // A signal cut the read short; that is not the user's cancel.
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            };
            to.write_all(&buf[..n])?;
            BYTES.fetch_add(n as u64, Atomic::Relaxed);
        }
        if let Some(t) = time {
            let _ = to.set_modified(t);
        }
        if how.sync {
            to.sync_all()?;
        }
        drop(to);
        fs::rename(&part, dest)
    };
    let result = run();
    if result.is_err() {
        let _ = fs::remove_file(&part);
    }
    result
}

/// A file, or a folder with all in it. Links are left out: a phone's
/// shared storage has none, and following one could loop.
fn copy_tree(src: &Path, dest: &Path, how: &mut Copier) -> io::Result<()> {
    let meta = fs::symlink_metadata(src)?;
    if meta.file_type().is_symlink() {
        return Ok(());
    }
    if !meta.is_dir() {
        return copy_file(src, dest, how);
    }
    fs::create_dir(dest)?;
    for child in fs::read_dir(src)? {
        let child = child?;
        copy_tree(&child.path(), &dest.join(child.file_name()), how)?;
    }
    Ok(())
}

/// Files and bytes under a path, links left out; stops counting at half a
/// million items.
fn tree_stats(path: &Path) -> (u64, u64) {
    let (mut files, mut bytes, mut seen) = (0u64, 0u64, 0u64);
    let mut stack = vec![path.to_path_buf()];
    while let Some(p) = stack.pop() {
        let Ok(meta) = fs::symlink_metadata(&p) else { continue };
        seen += 1;
        if seen > 500_000 {
            break;
        }
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            if let Ok(read) = fs::read_dir(&p) {
                stack.extend(read.flatten().map(|d| d.path()));
            }
        } else {
            files += 1;
            bytes += meta.len();
        }
    }
    (files, bytes)
}

fn remove_tree(path: &Path) {
    let _ = match fs::symlink_metadata(path) {
        Ok(m) if m.is_dir() => fs::remove_dir_all(path),
        _ => fs::remove_file(path),
    };
}

/// A folder may not be copied or moved into itself.
fn inside(src: &Path, dest_dir: &Path) -> bool {
    dest_dir == src || dest_dir.starts_with(src)
}

/// Copy to a path nothing has yet. A failed copy is taken away again.
fn copy_to(src: &Path, dest: &Path, sync: bool) -> Result<(), String> {
    copy_tree(src, dest, &mut Copier::new(sync)).map_err(|e| {
        // "Already there" means another program took the name first, and
        // what is there is not this copy's to remove.
        if e.kind() != io::ErrorKind::AlreadyExists {
            remove_tree(dest);
        }
        plain(&e)
    })
}

/// Move by copying: for a move to another volume. The source goes only
/// after the copy is on the disk with every file and every byte.
fn move_by_copy(src: &Path, dest: &Path) -> Result<(), String> {
    copy_to(src, dest, true)?;
    if tree_stats(src) != tree_stats(dest) {
        remove_tree(dest);
        return Err("The copy did not match, so nothing was moved.".into());
    }
    remove_tree(src);
    if exists(src) {
        return Err("It was copied, but the original could not be removed.".into());
    }
    Ok(())
}

/// Move to a path nothing has yet: a rename where the volume allows it.
fn move_to(src: &Path, dest: &Path) -> Result<(), String> {
    if exists(dest) {
        return Err("That name is taken.".into());
    }
    if fs::rename(src, dest).is_ok() {
        return Ok(());
    }
    if !exists(src) {
        return Err("It is not there any more.".into());
    }
    move_by_copy(src, dest)
}

/// Copy an item into a folder. A taken name gets a number.
#[uniffi::export]
pub fn pointer_copy(src: String, dest_dir: String) -> Done {
    let (from, dir) = (Path::new(&src), Path::new(&dest_dir));
    if pack_folder(&dest_dir) {
        return done(Err(SEALED.into()));
    }
    // An item inside an archive is copied out of it.
    if !exists(from) {
        if let Some((file, inner)) = packed(&src) {
            let dest = free_name(dir, &name_of(from));
            return done(unpack(&file, &inner, &dest).map(|_| Undo {
                kind: UndoKind::Copied,
                from: src.clone(),
                to: text_of(&dest),
            }));
        }
    }
    if inside(from, dir) {
        return done(Err("A folder cannot go into itself.".into()));
    }
    let dest = free_name(dir, &name_of(from));
    done(copy_to(from, &dest, false).map(|_| Undo { kind: UndoKind::Copied, from: src.clone(), to: text_of(&dest) }))
}

/// Move an item into a folder. A taken name gets a number.
#[uniffi::export]
pub fn pointer_move(src: String, dest_dir: String) -> Done {
    let (from, dir) = (Path::new(&src), Path::new(&dest_dir));
    if pack_folder(&dest_dir) {
        return done(Err(SEALED.into()));
    }
    if in_pack(&src) {
        return done(Err("It can be copied out of an archive, not moved.".into()));
    }
    if inside(from, dir) {
        return done(Err("A folder cannot go into itself.".into()));
    }
    if from.parent() == Some(dir) {
        return done(Err("It is here already.".into()));
    }
    let dest = free_name(dir, &name_of(from));
    done(move_to(from, &dest).map(|_| Undo { kind: UndoKind::Moved, from: src.clone(), to: text_of(&dest) }))
}

/// Give an item a new name in the folder it is in.
#[uniffi::export]
pub fn pointer_rename(path: String, new_name: String) -> Done {
    let from = Path::new(&path);
    let result = (|| {
        if in_pack(&path) {
            return Err(SEALED.to_string());
        }
        let name = good_name(&new_name)?;
        let dest = from.with_file_name(name);
        // "notes" to "Notes" names the same file where case is ignored.
        let same = name_of(from).to_lowercase() == name.to_lowercase();
        if name_of(from) == name {
            return Err("That is its name already.".to_string());
        }
        if exists(&dest) && !same {
            return Err("That name is taken.".to_string());
        }
        fs::rename(from, &dest).map_err(|e| plain(&e))?;
        Ok(Undo { kind: UndoKind::Renamed, from: path.clone(), to: text_of(&dest) })
    })();
    done(result)
}

/// Make a folder.
#[uniffi::export]
pub fn pointer_mkdir(dir: String, name: String) -> Done {
    let result = (|| {
        if pack_folder(&dir) {
            return Err(SEALED.to_string());
        }
        let dest = Path::new(&dir).join(good_name(&name)?);
        fs::create_dir(&dest).map_err(|e| plain(&e))?;
        Ok(Undo { kind: UndoKind::Made, from: String::new(), to: text_of(&dest) })
    })();
    done(result)
}

// ---------- trash ----------

static TRASH_COUNT: AtomicU64 = AtomicU64::new(0);

/// Move an item to the trash folder of its own volume. `roots` are the
/// volumes' top folders.
#[uniffi::export]
pub fn pointer_trash(path: String, roots: Vec<String>) -> Done {
    let result = (|| {
        let root = root_of(&path, &roots).ok_or("That is outside the phone's storage.")?;
        let trash = Path::new(&root).join(TRASH);
        let from = Path::new(&path);
        if path == root || from == trash || from.starts_with(&trash) {
            return Err("That cannot go to the trash.".to_string());
        }
        if !exists(from) {
            let why = if packed(&path).is_some() { SEALED } else { "It is not there any more." };
            return Err(why.to_string());
        }
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
        let id = trash.join(format!("{}-{}", now, TRASH_COUNT.fetch_add(1, Atomic::Relaxed)));
        fs::create_dir_all(&id).map_err(|e| plain(&e))?;
        // The note comes first: an item in the trash always says where it
        // came from.
        let note = format!("{}\n{}\n", path, now / 1000);
        if let Err(e) = fs::write(id.with_extension("info"), note)
            .and_then(|_| fs::rename(from, id.join(name_of(from))))
        {
            let _ = fs::remove_dir(&id);
            let _ = fs::remove_file(id.with_extension("info"));
            return Err(format!("It could not go to the trash. {}", plain(&e)));
        }
        Ok(Undo { kind: UndoKind::Trashed, from: path.clone(), to: text_of(&id) })
    })();
    done(result)
}

/// The one item in a trash folder, and where it came from.
fn trashed_at(id: &Path) -> Option<(PathBuf, String, i64)> {
    let item = fs::read_dir(id).ok()?.flatten().next()?.path();
    let note = fs::read_to_string(id.with_extension("info")).unwrap_or_default();
    let mut lines = note.lines();
    let from = lines.next().unwrap_or("").to_string();
    let when = lines.next().and_then(|w| w.parse().ok()).unwrap_or(0);
    Some((item, from, when))
}

/// What is in the trash on every volume, the newest first.
#[uniffi::export]
pub fn pointer_trash_list(roots: Vec<String>) -> Vec<Trashed> {
    let mut out = Vec::new();
    for root in &roots {
        let Ok(read) = fs::read_dir(Path::new(root.trim_end_matches('/')).join(TRASH)) else { continue };
        for id in read.flatten().map(|d| d.path()).filter(|p| p.is_dir()) {
            let Some((item, from, time)) = trashed_at(&id) else { continue };
            let is_dir = item.is_dir();
            let name = name_of(&item);
            out.push(Trashed {
                id: text_of(&id),
                kind: kind_of(&name, is_dir),
                size_text: if is_dir {
                    String::new()
                } else {
                    size_text(fs::metadata(&item).map_or(0, |m| m.len()))
                },
                name,
                from,
                time,
            });
        }
    }
    out.sort_by(|a, b| b.time.cmp(&a.time).then_with(|| b.id.cmp(&a.id)));
    out
}

/// Put a trashed item back where it was. If its old name is taken by now,
/// it gets a number.
#[uniffi::export]
pub fn pointer_restore(id: String) -> Done {
    let id_path = Path::new(&id);
    let result = (|| {
        let (item, from, _) = trashed_at(id_path).ok_or("It is not in the trash any more.")?;
        let from = Path::new(&from);
        let parent = from.parent().filter(|p| !p.as_os_str().is_empty()).ok_or("Where it came from is not known.")?;
        fs::create_dir_all(parent).map_err(|e| plain(&e))?;
        let dest = free_name(parent, &name_of(from));
        fs::rename(&item, &dest).map_err(|e| plain(&e))?;
        let _ = fs::remove_dir(id_path);
        let _ = fs::remove_file(id_path.with_extension("info"));
        Ok(Undo { kind: UndoKind::Moved, from: id.clone(), to: text_of(&dest) })
    })();
    // A restore is itself the undo of a delete; it offers no undo of its own.
    Done { undo: None, ..done(result) }
}

/// Delete what is in the trash, for good. Returns how many items went.
#[uniffi::export]
pub fn pointer_trash_empty(roots: Vec<String>) -> u32 {
    let mut gone = 0;
    for root in &roots {
        let trash = Path::new(root.trim_end_matches('/')).join(TRASH);
        let Ok(read) = fs::read_dir(&trash) else { continue };
        for p in read.flatten().map(|d| d.path()) {
            if p.is_dir() {
                gone += 1;
            }
            remove_tree(&p);
        }
    }
    gone
}

/// Take one step back.
#[uniffi::export]
pub fn pointer_undo(undo: Undo, roots: Vec<String>) -> Done {
    let result = match undo.kind {
        // The copy goes to the trash, so even an undo loses nothing.
        UndoKind::Copied => return Done { undo: None, ..pointer_trash(undo.to, roots) },
        UndoKind::Trashed => return pointer_restore(undo.to),
        UndoKind::Moved | UndoKind::Renamed => {
            let (now, back) = (Path::new(&undo.to), Path::new(&undo.from));
            match back.parent() {
                Some(parent) => move_to(now, &free_name(parent, &name_of(back))),
                None => Err("Where it came from is not known.".into()),
            }
        }
        // Only an empty folder goes; one that got files since stays.
        UndoKind::Made => fs::remove_dir(&undo.to).map_err(|_| "The folder is in use, so it stays.".to_string()),
    };
    Done { error: result.err().unwrap_or_default(), undo: None }
}

// ---------- search, sizes, preview ----------

static SEARCH: AtomicU64 = AtomicU64::new(0);

/// Stop the search that is running, if any.
#[uniffi::export]
pub fn pointer_search_stop() {
    SEARCH.fetch_add(1, Atomic::Relaxed);
}

/// Names under `root` with `query` in them, case ignored, the nearest first.
/// A new search stops the one before it. Hidden folders are searched only
/// when `hidden` is set; the trash never is.
#[uniffi::export]
pub fn pointer_search(root: String, query: String, hidden: bool, limit: u32) -> Vec<Entry> {
    let ticket = SEARCH.fetch_add(1, Atomic::Relaxed) + 1;
    let query = query.trim().to_lowercase();
    let mut out = Vec::new();
    if query.is_empty() {
        return out;
    }
    if pack_folder(&root) {
        return packed(&root).map_or(out, |(file, inner)| search_packed(&file, &inner, &query, hidden, limit as usize));
    }
    let mut queue = VecDeque::from([PathBuf::from(&root)]);
    let mut seen = 0u32;
    while let Some(dir) = queue.pop_front() {
        let Ok(read) = fs::read_dir(&dir) else { continue };
        for d in read.flatten() {
            seen += 1;
            if seen > 300_000 || SEARCH.load(Atomic::Relaxed) != ticket {
                return out;
            }
            let name = d.file_name().to_string_lossy().into_owned();
            if name == TRASH || (!hidden && name.starts_with('.')) {
                continue;
            }
            if name.to_lowercase().contains(&query) {
                out.extend(entry_of(&d.path()));
                if out.len() >= limit as usize {
                    return out;
                }
            }
            if d.file_type().map_or(false, |t| t.is_dir()) {
                queue.push_back(d.path());
            }
        }
    }
    out
}

/// The size of the given items, folders counted all the way down.
#[uniffi::export]
pub fn pointer_tree_size(paths: Vec<String>) -> TreeSize {
    let (mut files, mut bytes) = (0, 0);
    for p in &paths {
        let (f, b) = if in_pack(p) { pack_stats(p) } else { tree_stats(Path::new(p)) };
        files += f;
        bytes += b;
    }
    let noun = if files == 1 { "file" } else { "files" };
    TreeSize { bytes, files, text: format!("{} in {} {}", size_text(bytes), files, noun) }
}

/// The start of a file as text, at most `max` bytes; empty when the file
/// is not text.
#[uniffi::export]
pub fn pointer_text(path: String, max: u32) -> String {
    let mut buf = Vec::new();
    let Ok(file) = File::open(&path) else { return String::new() };
    if file.take(max as u64).read_to_end(&mut buf).is_err() {
        return String::new();
    }
    if buf.iter().take(4096).any(|&b| b == 0) {
        return String::new();
    }
    String::from_utf8_lossy(&buf).into_owned()
}

/// Folders that Syncthing shares, found by its marker, at most two steps
/// under the root.
#[uniffi::export]
pub fn pointer_synced(root: String) -> Vec<String> {
    let mut out = Vec::new();
    let dirs = |p: &Path| -> Vec<PathBuf> {
        fs::read_dir(p)
            .map(|r| r.flatten().map(|d| d.path()).filter(|p| p.is_dir()).collect())
            .unwrap_or_default()
    };
    for one in dirs(Path::new(&root)) {
        if name_of(&one) == TRASH {
            continue;
        }
        if one.join(".stfolder").exists() {
            out.push(text_of(&one));
            continue;
        }
        // Android/ is every app's own data; nothing is shared from there.
        if name_of(&one) == "Android" {
            continue;
        }
        out.extend(dirs(&one).into_iter().filter(|two| two.join(".stfolder").exists()).map(|p| text_of(&p)));
    }
    out.sort_by(|a, b| natural_cmp(a, b));
    out
}

// ---------- words inside files ----------

/// A file larger than this is not searched for words.
const GREP_MAX: u64 = 8 << 20;

/// Text files under `root` with `query` in them, case ignored, the nearest
/// first, each with the first line that has it. Pictures, sound, video and
/// archives are passed by on their names. Any other file is opened, and
/// read on only when its start looks like text. Stops like `pointer_search`.
#[uniffi::export]
pub fn pointer_grep(root: String, query: String, hidden: bool, limit: u32) -> Vec<Entry> {
    let ticket = SEARCH.fetch_add(1, Atomic::Relaxed) + 1;
    let needle = query.trim().to_lowercase();
    let mut out = Vec::new();
    if needle.is_empty() {
        return out;
    }
    let mut queue = VecDeque::from([PathBuf::from(&root)]);
    let mut seen = 0u32;
    let mut buf = Vec::new();
    while let Some(dir) = queue.pop_front() {
        let Ok(read) = fs::read_dir(&dir) else { continue };
        for d in read.flatten() {
            seen += 1;
            if seen > 300_000 || SEARCH.load(Atomic::Relaxed) != ticket {
                return out;
            }
            let name = d.file_name().to_string_lossy().into_owned();
            if name == TRASH || (!hidden && name.starts_with('.')) {
                continue;
            }
            if d.file_type().map_or(false, |t| t.is_dir()) {
                queue.push_back(d.path());
                continue;
            }
            if !matches!(kind_of(&name, false), Kind::Text | Kind::Other) {
                continue;
            }
            let path = d.path();
            let Some((line, note)) = line_with(&path, &needle, &mut buf) else { continue };
            out.extend(entry_of(&path).map(|e| Entry { line, note, ..e }));
            if out.len() >= limit as usize {
                return out;
            }
        }
    }
    out
}

/// The first line of a text file with `needle` in it. `buf` is the caller's,
/// so a search of many files asks for memory once.
fn line_with(path: &Path, needle: &str, buf: &mut Vec<u8>) -> Option<(u32, String)> {
    let mut file = File::open(path).ok()?;
    if file.metadata().ok()?.len() > GREP_MAX {
        return None;
    }
    buf.clear();
    // The first 4 KiB tell text from the rest. Only text is read on.
    (&mut file).take(4096).read_to_end(buf).ok()?;
    if buf.contains(&0) {
        return None;
    }
    file.read_to_end(buf).ok()?;
    first_hit(buf, needle)
}

/// The first line of `hay` with `needle` in it, case ignored: its number
/// from 1, and its text. `needle` is in lower case.
fn first_hit(hay: &[u8], needle: &str) -> Option<(u32, String)> {
    if needle.is_empty() {
        return None;
    }
    let newlines = |bytes: &[u8]| bytes.iter().filter(|&&b| b == b'\n').count();
    let (line, from) = if needle.is_ascii() {
        let at = hay.windows(needle.len()).position(|w| w.eq_ignore_ascii_case(needle.as_bytes()))?;
        let start = hay[..at].iter().rposition(|&b| b == b'\n').map_or(0, |i| i + 1);
        // Of a very long line, show the part the words are in.
        (newlines(&hay[..at]), if at - start > 80 { at - 40 } else { start })
    } else {
        // Lower case can change how many bytes a letter takes, so the place
        // is counted in lines, which it cannot change.
        let low = String::from_utf8_lossy(hay).to_lowercase();
        let line = newlines(&low.as_bytes()[..low.find(needle)?]);
        (line, hay.split(|&b| b == b'\n').take(line).map(|l| l.len() + 1).sum())
    };
    let rest = hay.get(from..)?;
    let end = rest.iter().position(|&b| b == b'\n').unwrap_or(rest.len());
    let text: String = String::from_utf8_lossy(&rest[..end])
        .trim_matches(|c: char| c.is_whitespace() || c == '\u{fffd}')
        .chars()
        .take(160)
        .collect();
    Some((line as u32 + 1, text))
}

// ---------- files from other apps ----------

/// A name for a file another app hands over: one path step, nothing a name
/// cannot have, and no dot in front, so the file does not land hidden.
#[uniffi::export]
pub fn pointer_safe_name(name: String) -> String {
    safe_name(&name)
}

fn safe_name(name: &str) -> String {
    let last = name.rsplit(['/', '\\']).next().unwrap_or("");
    let kept: String = last.chars().filter(|c| !c.is_control()).collect();
    let kept = kept.trim().trim_start_matches('.').trim();
    if kept.is_empty() {
        return "shared".into();
    }
    if kept.len() <= 200 {
        return kept.to_string();
    }
    // Too long for a file name: the end of the stem goes, the ending stays.
    let ext = match kept.rfind('.') {
        Some(i) if kept.len() - i <= 12 => &kept[i..],
        _ => "",
    };
    let mut end = 200 - ext.len();
    while !kept.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}{}", &kept[..end], ext)
}

/// Give a file its real name. The shell wrote it into its folder under a
/// waiting name, since only the shell can read what another app shares.
/// A taken name gets a number.
#[uniffi::export]
pub fn pointer_keep(part: String, name: String) -> Done {
    let from = Path::new(&part);
    let result = (|| {
        let dir = from.parent().ok_or("Where it should go is not known.")?;
        let dest = free_name(dir, &safe_name(&name));
        fs::rename(from, &dest).map_err(|e| {
            let _ = fs::remove_file(from);
            plain(&e)
        })?;
        Ok(Undo { kind: UndoKind::Copied, from: String::new(), to: text_of(&dest) })
    })();
    done(result)
}

/// Save text that another app shared as a file in `dir`.
#[uniffi::export]
pub fn pointer_save_text(dir: String, name: String, text: String) -> Done {
    if pack_folder(&dir) {
        return done(Err(SEALED.into()));
    }
    let dest = free_name(Path::new(&dir), &safe_name(&name));
    let part = dest.with_file_name(format!(".{}.part", name_of(&dest)));
    let result = fs::write(&part, text).and_then(|_| fs::rename(&part, &dest)).map_err(|e| {
        let _ = fs::remove_file(&part);
        plain(&e)
    });
    done(result.map(|_| Undo { kind: UndoKind::Copied, from: String::new(), to: text_of(&dest) }))
}

// ---------- archives as folders ----------

// A zip or tar file is walked like a folder: "/sdcard/a.zip/docs" is the
// folder "docs" inside a.zip. No such path is on the disk, so each function
// that is handed one finds the archive on the way to it. An archive is
// read and never changed: what is in it can be copied out, nothing more.

const SEALED: &str = "An archive is read here, not changed.";

#[derive(Clone, Copy, PartialEq)]
enum Pack {
    Zip,
    Tar,
    TarGz,
}

/// The kind of archive a name says it is; none for one not read here.
fn pack_of(name: &str) -> Option<Pack> {
    let name = name.to_ascii_lowercase();
    if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        Some(Pack::TarGz)
    } else if name.ends_with(".tar") {
        Some(Pack::Tar)
    } else if name.ends_with(".zip") || name.ends_with(".jar") {
        Some(Pack::Zip)
    } else {
        None
    }
}

/// True when a file of this name opens as a folder.
#[uniffi::export]
pub fn pointer_opens(name: String) -> bool {
    pack_of(&name).is_some()
}

/// The name without the archive ending: "photos" for "photos.tar.gz".
fn pack_stem(name: &str) -> &str {
    let lower = name.to_ascii_lowercase();
    [".tar.gz", ".tgz", ".tar", ".zip", ".jar"]
        .iter()
        .find(|end| lower.ends_with(*end) && name.len() > end.len())
        .and_then(|end| name.get(..name.len() - end.len()))
        .unwrap_or(name)
}

/// A path that runs through an archive: the archive's file, and the path
/// inside it ("" for its top). None for any other path, and that answer
/// costs a look at the names, no more.
fn packed(path: &str) -> Option<(PathBuf, String)> {
    let full = Path::new(path);
    let mut at = full;
    loop {
        if pack_of(&name_of(at)).is_some() && at.is_file() {
            let inner = text_of(full.strip_prefix(at).ok()?);
            return Some((at.to_path_buf(), inner.trim_matches('/').to_string()));
        }
        at = at.parent()?;
    }
}

/// True for an item inside an archive.
fn in_pack(path: &str) -> bool {
    !exists(Path::new(path)) && packed(path).is_some()
}

/// True for a folder that is an archive, or inside one.
fn pack_folder(dir: &str) -> bool {
    !Path::new(dir).is_dir() && packed(dir).is_some()
}

/// An error whose words are plain already.
#[derive(Debug)]
struct Said(&'static str);

impl std::fmt::Display for Said {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for Said {}

fn said(text: &'static str) -> io::Error {
    io::Error::other(Said(text))
}

/// Why work on an archive failed, in words a person can read. What the
/// unpacking code says about a broken archive is not such words.
fn pack_plain(e: &io::Error) -> String {
    if e.get_ref().is_some_and(|inner| inner.is::<Said>()) {
        return e.to_string();
    }
    match e.kind() {
        io::ErrorKind::InvalidData | io::ErrorKind::InvalidInput | io::ErrorKind::UnexpectedEof | io::ErrorKind::Other => {
            "This archive cannot be read.".into()
        }
        _ => plain(e),
    }
}

fn zip_error(e: zip::result::ZipError) -> io::Error {
    use zip::result::ZipError;
    match e {
        ZipError::Io(e) => e,
        ZipError::InvalidPassword => said("It is locked with a password."),
        ZipError::UnsupportedArchive(why) if why == ZipError::PASSWORD_REQUIRED => said("It is locked with a password."),
        ZipError::CompressionMethodNotSupported(_) => said("It is packed in a way this app cannot unpack."),
        _ => said("This archive cannot be read."),
    }
}

/// One item of an archive.
struct Packed {
    /// Its path inside the archive: "docs/a.txt".
    path: String,
    dir: bool,
    size: u64,
    /// Seconds since 1970; 0 when the archive does not say.
    modified: i64,
    /// A zip finds its items by number.
    at: usize,
}

/// What is in an archive.
struct Index {
    file: PathBuf,
    /// The file's size and time when it was read.
    stamp: (u64, Option<SystemTime>),
    items: Vec<Packed>,
}

// The archive last walked. Each step into one of its folders would
// otherwise read the list again, and a .tar.gz has to be unpacked from
// its start to be listed. A changed file is read anew.
static INDEX: Mutex<Option<Arc<Index>>> = Mutex::new(None);

fn index_of(file: &Path) -> Result<Arc<Index>, String> {
    let meta = fs::metadata(file).map_err(|e| plain(&e))?;
    let stamp = (meta.len(), meta.modified().ok());
    let mut kept = INDEX.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(index) = kept.as_ref().filter(|i| i.file == file && i.stamp == stamp) {
        return Ok(index.clone());
    }
    let items = with_parents(read_index(file).map_err(|e| pack_plain(&e))?);
    let index = Arc::new(Index { file: file.to_path_buf(), stamp, items });
    *kept = Some(index.clone());
    Ok(index)
}

fn open_zip(file: &Path) -> io::Result<zip::ZipArchive<BufReader<File>>> {
    zip::ZipArchive::new(BufReader::new(File::open(file)?)).map_err(zip_error)
}

fn read_index(file: &Path) -> io::Result<Vec<Packed>> {
    let mut items = Vec::new();
    match pack_of(&name_of(file)) {
        Some(Pack::Zip) => {
            let mut zip = open_zip(file)?;
            for at in 0..zip.len() {
                let Ok(f) = zip.by_index_raw(at) else { continue };
                let Some(path) = f.enclosed_name().and_then(|p| clean(&p)) else { continue };
                // A zip's own time has no time zone. Only the exact time
                // some zips carry beside it is used.
                let modified = f
                    .extra_data_fields()
                    .find_map(|x| match x {
                        zip::ExtraField::ExtendedTimestamp(t) => t.mod_time().map(i64::from),
                        _ => None,
                    })
                    .unwrap_or(0);
                items.push(Packed { path, dir: f.is_dir(), size: f.size(), modified, at });
            }
        }
        Some(_) => each_tar(file, |path, dir, size, modified, _| {
            items.push(Packed { path, dir, size, modified, at: 0 });
            Ok(true)
        })?,
        None => return Err(said("This kind of archive is not read here.")),
    }
    Ok(items)
}

/// Walk a tar file from its start. `each` gets an item's path, whether it
/// is a folder, its size, its time and its bytes, and says whether to go
/// on. Links and devices are left out: only files and folders count.
fn each_tar(
    file: &Path,
    mut each: impl FnMut(String, bool, u64, i64, &mut dyn Read) -> io::Result<bool>,
) -> io::Result<()> {
    let raw = BufReader::new(File::open(file)?);
    let bytes: Box<dyn Read> = match pack_of(&name_of(file)) {
        Some(Pack::TarGz) => Box::new(flate2::read::MultiGzDecoder::new(raw)),
        _ => Box::new(raw),
    };
    let mut tar = tar::Archive::new(bytes);
    for entry in tar.entries()? {
        let mut entry = entry?;
        let kind = entry.header().entry_type();
        if !kind.is_dir() && !kind.is_file() {
            continue;
        }
        let Some(path) = entry.path().ok().and_then(|p| clean(&p)) else { continue };
        let (size, time) = (entry.size(), entry.header().mtime().unwrap_or(0) as i64);
        if !each(path, kind.is_dir(), size, time, &mut entry)? {
            break;
        }
    }
    Ok(())
}

/// A path from an archive as plain steps: "docs/a.txt". None for one with
/// ".." in it: unpacked, it would land outside the folder it is unpacked to.
fn clean(path: &Path) -> Option<String> {
    let mut steps: Vec<String> = Vec::new();
    for part in path.components() {
        match part {
            Component::Normal(step) => steps.push(step.to_string_lossy().into_owned()),
            Component::ParentDir => return None,
            // A leading "/" or "./" is dropped.
            _ => {}
        }
    }
    (!steps.is_empty()).then(|| steps.join("/"))
}

/// Every folder on the way to an item gets an item of its own, before the
/// item, since many archives list only their files. No path stays twice.
fn with_parents(items: Vec<Packed>) -> Vec<Packed> {
    let mut seen: HashSet<String> = HashSet::new();
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        for (i, _) in item.path.match_indices('/') {
            let parent = &item.path[..i];
            if !seen.contains(parent) {
                seen.insert(parent.to_string());
                out.push(Packed { path: parent.to_string(), dir: true, size: 0, modified: 0, at: 0 });
            }
        }
        if seen.insert(item.path.clone()) {
            out.push(item);
        }
    }
    out
}

/// The rest of `path` under the folder `dir`: "" for `dir` itself, None
/// when the path is elsewhere.
fn below<'a>(path: &'a str, dir: &str) -> Option<&'a str> {
    if dir.is_empty() {
        return Some(path);
    }
    let rest = path.strip_prefix(dir)?;
    if rest.is_empty() {
        Some(rest)
    } else {
        rest.strip_prefix('/')
    }
}

fn packed_entry(file: &Path, item: &Packed) -> Entry {
    let name = item.path.rsplit('/').next().unwrap_or("").to_string();
    Entry {
        hidden: name.starts_with('.'),
        kind: kind_of(&name, item.dir),
        path: format!("{}/{}", text_of(file), item.path),
        size: if item.dir { 0 } else { item.size },
        size_text: if item.dir { String::new() } else { size_text(item.size) },
        modified: item.modified,
        line: 0,
        note: String::new(),
        name,
    }
}

/// The items of one folder inside an archive; "" is its top.
fn list_packed(file: &Path, inner: &str, sort: SortBy, reverse: bool, hidden: bool) -> Listing {
    let failed = |error: String| Listing { entries: Vec::new(), error, packed: true };
    let index = match index_of(file) {
        Ok(index) => index,
        Err(error) => return failed(error),
    };
    if !inner.is_empty() && !index.items.iter().any(|it| it.dir && it.path == inner) {
        return failed("It is not there any more.".into());
    }
    let mut entries: Vec<Entry> = index
        .items
        .iter()
        .filter(|it| below(&it.path, inner).is_some_and(|rest| !rest.is_empty() && !rest.contains('/')))
        .map(|it| packed_entry(file, it))
        .filter(|e| hidden || !e.hidden)
        .collect();
    sort_entries(&mut entries, sort, reverse);
    Listing { entries, error: String::new(), packed: true }
}

/// Names with `query` in them, below a folder inside an archive.
fn search_packed(file: &Path, inner: &str, query: &str, hidden: bool, limit: usize) -> Vec<Entry> {
    let Ok(index) = index_of(file) else { return Vec::new() };
    index
        .items
        .iter()
        .filter(|it| {
            below(&it.path, inner).is_some_and(|rest| {
                !rest.is_empty()
                    && (hidden || !rest.split('/').any(|step| step.starts_with('.')))
                    && rest.rsplit('/').next().is_some_and(|name| name.to_lowercase().contains(query))
            })
        })
        .take(limit)
        .map(|it| packed_entry(file, it))
        .collect()
}

/// Files and bytes of an item inside an archive, as the archive lists them.
fn pack_stats(path: &str) -> (u64, u64) {
    let Some((file, inner)) = packed(path) else { return (0, 0) };
    let Ok(index) = index_of(&file) else { return (0, 0) };
    index
        .items
        .iter()
        .filter(|it| !it.dir && below(&it.path, &inner).is_some())
        .fold((0, 0), |(files, bytes), it| (files + 1, bytes + it.size))
}

/// Copy the item at `inner` out of an archive to `dest`, a path nothing has
/// yet; "" stands for all of the archive. What a failed or cancelled copy
/// made is taken away again.
fn unpack(file: &Path, inner: &str, dest: &Path) -> Result<(), String> {
    let index = index_of(file)?;
    let is_dir = inner.is_empty() || index.items.iter().any(|it| it.dir && it.path == inner);
    if !is_dir && !index.items.iter().any(|it| it.path == inner) {
        return Err("It is not there any more.".into());
    }
    let mut how = Copier::new(false);
    // One item to its place under `dest`. `rel` has no ".." in it: `clean`
    // saw to that, so nothing lands outside `dest`.
    let mut put = |rel: &str, dir: bool, time: i64, from: &mut dyn Read| -> io::Result<()> {
        if CANCEL.load(Atomic::Relaxed) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }
        let to = if rel.is_empty() { dest.to_path_buf() } else { dest.join(rel) };
        if dir {
            return fs::create_dir_all(&to);
        }
        if let Some(parent) = to.parent() {
            fs::create_dir_all(parent)?;
        }
        let time = (time > 0).then(|| UNIX_EPOCH + Duration::from_secs(time as u64));
        write_file(from, &to, time, &mut how)
    };
    let result = (|| -> io::Result<()> {
        if is_dir {
            fs::create_dir(dest)?;
        }
        if pack_of(&name_of(file)) != Some(Pack::Zip) {
            // A tar is read from its start; a lone file ends the walk.
            return each_tar(file, |path, dir, _, time, from| match below(&path, inner) {
                Some(rel) => put(rel, dir, time, from).map(|_| is_dir),
                None => Ok(true),
            });
        }
        let mut zip = open_zip(file)?;
        for item in &index.items {
            let Some(rel) = below(&item.path, inner) else { continue };
            if item.dir {
                put(rel, true, 0, &mut io::empty())?;
            } else {
                put(rel, false, item.modified, &mut zip.by_index(item.at).map_err(zip_error)?)?;
            }
        }
        Ok(())
    })();
    result.map_err(|e| {
        if e.kind() != io::ErrorKind::AlreadyExists {
            remove_tree(dest);
        }
        pack_plain(&e)
    })
}

/// A file copied out of an archive to be looked at.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Unpacked {
    /// Where the copy is; empty when it could not be made.
    pub path: String,
    pub error: String,
}

/// Copy one file out of an archive into `cache`, to show it or to hand it
/// to another app. A second ask for the same file finds the first copy.
#[uniffi::export]
pub fn pointer_unpack(path: String, cache: String) -> Unpacked {
    let result: Result<String, String> = (|| {
        let (file, inner) = packed(&path).ok_or("It is not in an archive.")?;
        let index = index_of(&file)?;
        if !index.items.iter().any(|it| !it.dir && it.path == inner) {
            return Err("It is not there any more.".into());
        }
        // The folder's name is made of the archive, its time and the path
        // inside it, so a changed archive gets a fresh copy.
        let mut hash = DefaultHasher::new();
        (&file, index.stamp, &inner).hash(&mut hash);
        let dir = Path::new(&cache).join(format!("{:016x}", hash.finish()));
        let dest = dir.join(name_of(Path::new(&inner)));
        if !dest.is_file() {
            fs::create_dir_all(&dir).map_err(|e| plain(&e))?;
            unpack(&file, &inner, &dest)?;
        }
        Ok(text_of(&dest))
    })();
    match result {
        Ok(path) => Unpacked { path, error: String::new() },
        Err(error) => Unpacked { path: String::new(), error },
    }
}

/// Unpack a whole archive into a new folder named after it, in `dest_dir`.
#[uniffi::export]
pub fn pointer_unpack_here(archive: String, dest_dir: String) -> Done {
    let result = (|| {
        if pack_folder(&dest_dir) {
            return Err(SEALED.to_string());
        }
        let (file, _) = packed(&archive)
            .filter(|(_, inner)| inner.is_empty())
            .ok_or("This is not an archive that can be unpacked here.")?;
        let dest = free_name(Path::new(&dest_dir), pack_stem(&name_of(&file)));
        unpack(&file, "", &dest)?;
        Ok(Undo { kind: UndoKind::Copied, from: archive.clone(), to: text_of(&dest) })
    })();
    done(result)
}

// ---------- marks and recent folders ----------

/// Marks from the text the shell keeps: one "name<TAB>path" per line.
#[uniffi::export]
pub fn pointer_marks_parse(text: String) -> Vec<Mark> {
    text.lines()
        .filter_map(|l| l.split_once('\t'))
        .filter(|(name, path)| !name.is_empty() && !path.is_empty())
        .map(|(name, path)| Mark { name: name.to_string(), path: path.to_string() })
        .collect()
}

#[uniffi::export]
pub fn pointer_marks_text(marks: Vec<Mark>) -> String {
    marks.iter().map(|m| format!("{}\t{}\n", m.name.replace(['\t', '\n'], " "), m.path)).collect()
}

/// Put a folder first in the list of recent ones: one path per line, no
/// path twice, at most `max` of them.
#[uniffi::export]
pub fn pointer_recent_push(text: String, path: String, max: u32) -> String {
    std::iter::once(path.as_str())
        .chain(text.lines().filter(|l| *l != path && !l.is_empty()))
        .take(max as usize)
        .map(|l| format!("{}\n", l))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // The job flags are shared, so tests that copy run one at a time.
    static JOB: Mutex<()> = Mutex::new(());

    fn lock() -> std::sync::MutexGuard<'static, ()> {
        let guard = JOB.lock().unwrap_or_else(|e| e.into_inner());
        pointer_job_start();
        guard
    }

    // A new search stops the one before it, so tests that search run one
    // at a time too.
    static SEARCHES: Mutex<()> = Mutex::new(());

    fn searching() -> std::sync::MutexGuard<'static, ()> {
        SEARCHES.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A fresh folder for one test.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("pointer-test-{}-{}", std::process::id(), name));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn s(p: &Path) -> String {
        text_of(p)
    }

    fn names(dir: &Path) -> Vec<String> {
        pointer_list(s(dir), SortBy::Name, false, true).entries.into_iter().map(|e| e.name).collect()
    }

    #[test]
    fn names_sort_the_way_people_read_them() {
        let dir = scratch("sort");
        for f in ["img10.jpg", "img2.jpg", "Img1.jpg", ".secret", "b.txt"] {
            fs::write(dir.join(f), "x").unwrap();
        }
        fs::create_dir(dir.join("zebra")).unwrap();
        let shown = pointer_list(s(&dir), SortBy::Name, false, false);
        let got: Vec<&str> = shown.entries.iter().map(|e| e.name.as_str()).collect();
        // The folder first, the hidden file left out, 2 before 10.
        assert_eq!(got, vec!["zebra", "b.txt", "Img1.jpg", "img2.jpg", "img10.jpg"]);
        assert_eq!(shown.entries[2].kind, Kind::Image);
        assert_eq!(names(&dir).len(), 6);
        let reversed = pointer_list(s(&dir), SortBy::Name, true, false);
        assert_eq!(reversed.entries[0].name, "zebra");
        assert_eq!(reversed.entries[1].name, "img10.jpg");
        assert!(!pointer_list(s(&dir.join("none")), SortBy::Name, false, false).error.is_empty());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn sorting_by_size_and_time() {
        let dir = scratch("size");
        fs::write(dir.join("small"), "1").unwrap();
        fs::write(dir.join("big"), "12345").unwrap();
        let by_size = pointer_list(s(&dir), SortBy::Size, false, false);
        assert_eq!(by_size.entries[0].name, "big");
        assert_eq!(by_size.entries[0].size_text, "5 B");
        assert_eq!(size_text(1536), "1.5 KB");
        assert_eq!(size_text(12 * 1024 * 1024), "12 MB");
        assert_eq!(natural_cmp("a2", "a10"), Ordering::Less);
        assert_eq!(natural_cmp("a02", "a2"), Ordering::Equal);
        assert_eq!(natural_cmp("abc", "ABD"), Ordering::Less);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_copy_never_overwrites() {
        let _job = lock();
        let dir = scratch("copy");
        fs::write(dir.join("a.txt"), "first").unwrap();
        let one = pointer_copy(s(&dir.join("a.txt")), s(&dir));
        let two = pointer_copy(s(&dir.join("a.txt")), s(&dir));
        assert_eq!((one.error.as_str(), two.error.as_str()), ("", ""));
        assert_eq!(names(&dir), vec!["a (2).txt", "a (3).txt", "a.txt"]);
        assert_eq!(fs::read_to_string(dir.join("a (3).txt")).unwrap(), "first");
        assert_eq!(pointer_job_bytes(), 10);
        // The copy keeps the time the file was changed.
        let time = |p: &str| fs::metadata(dir.join(p)).unwrap().modified().unwrap();
        assert_eq!(time("a.txt"), time("a (2).txt"));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_folder_is_copied_with_all_in_it_but_not_into_itself() {
        let _job = lock();
        let dir = scratch("tree");
        fs::create_dir_all(dir.join("box/inner")).unwrap();
        fs::write(dir.join("box/one"), "1").unwrap();
        fs::write(dir.join("box/inner/two"), "22").unwrap();
        fs::create_dir(dir.join("there")).unwrap();
        assert_eq!(pointer_copy(s(&dir.join("box")), s(&dir.join("there"))).error, "");
        assert_eq!(fs::read_to_string(dir.join("there/box/inner/two")).unwrap(), "22");
        let size = pointer_tree_size(vec![s(&dir.join("there"))]);
        assert_eq!((size.files, size.bytes, size.text.as_str()), (2, 3, "3 B in 2 files"));
        assert!(!pointer_copy(s(&dir.join("box")), s(&dir.join("box/inner"))).error.is_empty());
        assert!(!pointer_move(s(&dir.join("box")), s(&dir.join("box"))).error.is_empty());
        assert!(!dir.join("box/inner/box").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_cancelled_copy_leaves_nothing_behind() {
        let _job = lock();
        let dir = scratch("cancel");
        fs::write(dir.join("a.bin"), vec![7u8; 4096]).unwrap();
        fs::create_dir(dir.join("to")).unwrap();
        pointer_job_cancel();
        let out = pointer_copy(s(&dir.join("a.bin")), s(&dir.join("to")));
        assert_eq!(out.error, "Cancelled.");
        assert!(out.undo.is_none());
        assert!(names(&dir.join("to")).is_empty());
        pointer_job_start();
        assert_eq!(pointer_copy(s(&dir.join("a.bin")), s(&dir.join("to"))).error, "");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_move_can_be_undone() {
        let _job = lock();
        let dir = scratch("move");
        fs::create_dir(dir.join("to")).unwrap();
        fs::write(dir.join("a.txt"), "a").unwrap();
        fs::write(dir.join("to/a.txt"), "other").unwrap();
        assert_eq!(pointer_move(s(&dir.join("a.txt")), s(&dir)).error, "It is here already.");
        let moved = pointer_move(s(&dir.join("a.txt")), s(&dir.join("to")));
        // The name was taken, so the moved file got a number.
        assert_eq!(names(&dir.join("to")), vec!["a (2).txt", "a.txt"]);
        assert_eq!(fs::read_to_string(dir.join("to/a.txt")).unwrap(), "other");
        assert!(!dir.join("a.txt").exists());
        assert_eq!(pointer_undo(moved.undo.unwrap(), vec![s(&dir)]).error, "");
        assert_eq!(fs::read_to_string(dir.join("a.txt")).unwrap(), "a");
        assert_eq!(names(&dir.join("to")), vec!["a.txt"]);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_move_to_another_volume_copies_checks_and_then_removes() {
        let _job = lock();
        let dir = scratch("cross");
        fs::create_dir_all(dir.join("box/inner")).unwrap();
        fs::write(dir.join("box/inner/two"), "22").unwrap();
        assert_eq!(move_by_copy(&dir.join("box"), &dir.join("moved")), Ok(()));
        assert!(!dir.join("box").exists());
        assert_eq!(fs::read_to_string(dir.join("moved/inner/two")).unwrap(), "22");
        // A cancelled one keeps the source and takes the half copy away.
        pointer_job_cancel();
        assert!(move_by_copy(&dir.join("moved"), &dir.join("again")).is_err());
        assert!(dir.join("moved/inner/two").exists() && !dir.join("again").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_move_between_two_real_volumes() {
        use std::os::unix::fs::MetadataExt;
        let _job = lock();
        let here = scratch("volume-a");
        let there = PathBuf::from(format!("/dev/shm/pointer-test-{}", std::process::id()));
        if fs::create_dir_all(&there).is_err() {
            return;
        }
        let dev = |p: &Path| fs::metadata(p).map(|m| m.dev()).unwrap_or(0);
        if dev(&here) != dev(&there) {
            fs::create_dir_all(here.join("box/inner")).unwrap();
            fs::write(here.join("box/inner/two"), vec![5u8; 3_000_000]).unwrap();
            let moved = pointer_move(s(&here.join("box")), s(&there));
            assert_eq!(moved.error, "");
            assert!(!here.join("box").exists());
            assert_eq!(fs::metadata(there.join("box/inner/two")).unwrap().len(), 3_000_000);
            assert_eq!(pointer_job_bytes(), 3_000_000);
            // And back again, by undo.
            assert_eq!(pointer_undo(moved.undo.unwrap(), Vec::new()).error, "");
            assert_eq!(fs::metadata(here.join("box/inner/two")).unwrap().len(), 3_000_000);
            assert!(!there.join("box").exists());
        }
        fs::remove_dir_all(&there).unwrap();
        fs::remove_dir_all(here).unwrap();
    }

    #[test]
    fn rename_refuses_bad_and_taken_names() {
        let dir = scratch("rename");
        fs::write(dir.join("a"), "a").unwrap();
        fs::write(dir.join("b"), "b").unwrap();
        let a = s(&dir.join("a"));
        for bad in ["", "  ", ".", "..", "x/y", "b", "a"] {
            assert!(!pointer_rename(a.clone(), bad.into()).error.is_empty(), "{:?}", bad);
        }
        assert_eq!(fs::read_to_string(dir.join("b")).unwrap(), "b");
        let out = pointer_rename(a, " c ".into());
        assert_eq!(names(&dir), vec!["b", "c"]);
        assert_eq!(pointer_undo(out.undo.unwrap(), Vec::new()).error, "");
        assert_eq!(names(&dir), vec!["a", "b"]);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_new_folder_and_its_undo() {
        let dir = scratch("mkdir");
        let made = pointer_mkdir(s(&dir), "new".into());
        assert_eq!(made.error, "");
        assert_eq!(pointer_mkdir(s(&dir), "new".into()).error, "That name is taken.");
        assert!(!pointer_mkdir(s(&dir), "a/b".into()).error.is_empty());
        assert_eq!(pointer_undo(made.undo.clone().unwrap(), Vec::new()).error, "");
        assert!(names(&dir).is_empty());
        // A folder that got a file since stays.
        fs::create_dir(dir.join("new")).unwrap();
        fs::write(dir.join("new/x"), "x").unwrap();
        assert!(!pointer_undo(made.undo.unwrap(), Vec::new()).error.is_empty());
        assert!(dir.join("new/x").exists());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_trash_keeps_gives_back_and_empties() {
        let _one = searching();
        let dir = scratch("trash");
        let roots = vec![s(&dir)];
        fs::create_dir(dir.join("docs")).unwrap();
        fs::write(dir.join("docs/a.txt"), "a").unwrap();
        fs::write(dir.join("b.txt"), "bb").unwrap();

        let gone = pointer_trash(s(&dir.join("docs/a.txt")), roots.clone());
        assert_eq!(gone.error, "");
        assert!(!dir.join("docs/a.txt").exists());
        let list = pointer_trash_list(roots.clone());
        assert_eq!(list.len(), 1);
        assert_eq!((list[0].name.as_str(), list[0].size_text.as_str()), ("a.txt", "1 B"));
        assert_eq!(list[0].from, s(&dir.join("docs/a.txt")));
        // The trash does not show in a listing, hidden files or not, or in a search.
        assert_eq!(names(&dir), vec!["docs", "b.txt"]);
        assert!(pointer_search(s(&dir), "a.txt".into(), true, 10).is_empty());

        // Its old name got taken, and its folder went: it still comes back.
        fs::remove_dir(dir.join("docs")).unwrap();
        assert_eq!(pointer_undo(gone.undo.unwrap(), roots.clone()).error, "");
        assert_eq!(fs::read_to_string(dir.join("docs/a.txt")).unwrap(), "a");
        assert!(pointer_trash_list(roots.clone()).is_empty());

        // What may not go: the volume, the trash, things outside, things gone.
        assert!(!pointer_trash(s(&dir), roots.clone()).error.is_empty());
        assert!(!pointer_trash(s(&dir.join(TRASH)), roots.clone()).error.is_empty());
        assert!(!pointer_trash("/etc/hostname".into(), roots.clone()).error.is_empty());
        assert!(!pointer_trash(s(&dir.join("nothing")), roots.clone()).error.is_empty());

        assert_eq!(pointer_trash(s(&dir.join("b.txt")), roots.clone()).error, "");
        assert_eq!(pointer_trash(s(&dir.join("docs")), roots.clone()).error, "");
        assert_eq!(pointer_trash_empty(roots.clone()), 2);
        assert!(pointer_trash_list(roots).is_empty());
        assert_eq!(fs::read_dir(dir.join(TRASH)).unwrap().count(), 0);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn undoing_a_copy_sends_the_copy_to_the_trash() {
        let _job = lock();
        let dir = scratch("uncopy");
        fs::write(dir.join("a"), "a").unwrap();
        let copied = pointer_copy(s(&dir.join("a")), s(&dir));
        assert_eq!(pointer_undo(copied.undo.unwrap(), vec![s(&dir)]).error, "");
        assert_eq!(names(&dir), vec!["a"]);
        assert_eq!(pointer_trash_list(vec![s(&dir)]).len(), 1);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn search_finds_names_below_and_skips_hidden_folders() {
        let _one = searching();
        let dir = scratch("search");
        fs::create_dir_all(dir.join("a/b")).unwrap();
        fs::create_dir_all(dir.join(".cache")).unwrap();
        fs::write(dir.join("a/b/Report.pdf"), "x").unwrap();
        fs::write(dir.join("report.txt"), "x").unwrap();
        fs::write(dir.join(".cache/report.tmp"), "x").unwrap();
        let found = pointer_search(s(&dir), "report".into(), false, 50);
        let got: Vec<&str> = found.iter().map(|e| e.name.as_str()).collect();
        // The nearest first.
        assert_eq!(got, vec!["report.txt", "Report.pdf"]);
        assert_eq!(pointer_search(s(&dir), "report".into(), true, 50).len(), 3);
        assert_eq!(pointer_search(s(&dir), "report".into(), true, 1).len(), 1);
        assert!(pointer_search(s(&dir), "  ".into(), true, 50).is_empty());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn text_marks_recents_crumbs_and_synced_folders() {
        let dir = scratch("misc");
        fs::write(dir.join("t.txt"), "hello world").unwrap();
        fs::write(dir.join("b.bin"), [1u8, 0, 2]).unwrap();
        assert_eq!(pointer_text(s(&dir.join("t.txt")), 5), "hello");
        assert_eq!(pointer_text(s(&dir.join("b.bin")), 100), "");
        assert_eq!(pointer_text(s(&dir.join("none")), 100), "");

        let marks = vec![Mark { name: "Work\tdocs".into(), path: "/a/b".into() }];
        let text = pointer_marks_text(marks);
        assert_eq!(text, "Work docs\t/a/b\n");
        assert_eq!(pointer_marks_parse(text + "junk\n\t\n"), vec![Mark { name: "Work docs".into(), path: "/a/b".into() }]);

        assert_eq!(pointer_recent_push("/a\n/b\n/c\n".into(), "/b".into(), 3), "/b\n/a\n/c\n");
        assert_eq!(pointer_recent_push("/a\n/b\n".into(), "/z".into(), 2), "/z\n/a\n");

        let roots = vec!["/storage/emulated/0".to_string(), "/storage/emulated/0/sub".to_string()];
        let crumbs = pointer_crumbs("/storage/emulated/0/DCIM/Camera".into(), roots.clone());
        let got: Vec<(&str, &str)> = crumbs.iter().map(|m| (m.name.as_str(), m.path.as_str())).collect();
        assert_eq!(got, vec![("0", "/storage/emulated/0"), ("DCIM", "/storage/emulated/0/DCIM"),
            ("Camera", "/storage/emulated/0/DCIM/Camera")]);
        assert!(pointer_crumbs("/storage/emulated/01".into(), roots).is_empty());

        fs::create_dir_all(dir.join("Sync/.stfolder")).unwrap();
        fs::create_dir_all(dir.join("Documents/notes/.stfolder")).unwrap();
        fs::create_dir_all(dir.join("Android/data/.stfolder")).unwrap();
        assert_eq!(pointer_synced(s(&dir)), vec![s(&dir.join("Documents/notes")), s(&dir.join("Sync"))]);
        assert_eq!(kind_of("a.tar.GZ", false), Kind::Archive);
        assert_eq!(kind_of(".bashrc", false), Kind::Other);
        assert_eq!(kind_of("photos.jpg", true), Kind::Dir);
        fs::remove_dir_all(dir).unwrap();
    }

    /// A zip with a file, a file two folders down, an empty folder, and one
    /// item whose path points out of the archive.
    fn zip_at(path: &Path) {
        use zip::write::SimpleFileOptions;
        let stored = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
        let squeezed = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        let mut zip = zip::ZipWriter::new(File::create(path).unwrap());
        zip.start_file("readme.txt", stored).unwrap();
        zip.write_all(b"hello").unwrap();
        zip.start_file("docs/deep/Notes.md", squeezed).unwrap();
        zip.write_all(&b"line one\nline two\n".repeat(100)).unwrap();
        zip.add_directory("empty", stored).unwrap();
        zip.start_file("../outside.txt", stored).unwrap();
        zip.write_all(b"no").unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn a_zip_is_walked_like_a_folder() {
        let _one = searching();
        let dir = scratch("zip-walk");
        let zip = dir.join("a.zip");
        zip_at(&zip);
        assert!(pointer_opens("A.ZIP".into()) && pointer_opens("b.tar.gz".into()) && !pointer_opens("c.7z".into()));

        let top = pointer_list(s(&zip), SortBy::Name, false, false);
        assert_eq!((top.error.as_str(), top.packed), ("", true));
        let got: Vec<(&str, Kind)> = top.entries.iter().map(|e| (e.name.as_str(), e.kind)).collect();
        // The item that pointed out of the archive is not among them.
        assert_eq!(got, vec![("docs", Kind::Dir), ("empty", Kind::Dir), ("readme.txt", Kind::Text)]);
        assert_eq!(top.entries[2].size_text, "5 B");
        assert_eq!(top.entries[0].path, s(&zip.join("docs")));

        let deep = pointer_list(s(&zip.join("docs/deep")), SortBy::Name, false, false);
        assert_eq!(deep.entries.len(), 1);
        assert_eq!((deep.entries[0].name.as_str(), deep.entries[0].size), ("Notes.md", 1800));
        assert!(pointer_list(s(&zip.join("empty")), SortBy::Name, false, false).entries.is_empty());
        assert!(!pointer_list(s(&zip.join("none")), SortBy::Name, false, false).error.is_empty());
        // A plain folder is not an archive, and a broken archive says so.
        assert!(!pointer_list(s(&dir), SortBy::Name, false, false).packed);
        fs::write(dir.join("bad.zip"), "not a zip").unwrap();
        assert_eq!(pointer_list(s(&dir.join("bad.zip")), SortBy::Name, false, false).error, "This archive cannot be read.");

        let found = pointer_search(s(&zip), "notes".into(), false, 10);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].path, s(&zip.join("docs/deep/Notes.md")));
        assert!(pointer_search(s(&zip.join("empty")), "notes".into(), false, 10).is_empty());
        let size = pointer_tree_size(vec![s(&zip.join("docs")), s(&zip.join("readme.txt"))]);
        assert_eq!((size.files, size.bytes), (2, 1805));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn items_are_copied_out_of_an_archive_and_nothing_else() {
        let _job = lock();
        let dir = scratch("zip-out");
        let zip = dir.join("a.zip");
        zip_at(&zip);
        let out = dir.join("out");
        fs::create_dir(&out).unwrap();

        let one = pointer_copy(s(&zip.join("readme.txt")), s(&out));
        assert_eq!(one.error, "");
        assert_eq!(fs::read_to_string(out.join("readme.txt")).unwrap(), "hello");
        let tree = pointer_copy(s(&zip.join("docs")), s(&out));
        assert_eq!(tree.error, "");
        assert_eq!(fs::metadata(out.join("docs/deep/Notes.md")).unwrap().len(), 1800);
        // A taken name gets a number, here too.
        assert_eq!(pointer_copy(s(&zip.join("docs")), s(&out)).error, "");
        assert_eq!(names(&out), vec!["docs", "docs (2)", "readme.txt"]);
        assert_eq!(pointer_copy(s(&zip.join("none")), s(&out)).error, "It is not there any more.");

        // The archive itself is not changed by any of these.
        let before = fs::read(&zip).unwrap();
        assert!(!pointer_move(s(&zip.join("readme.txt")), s(&out)).error.is_empty());
        assert_eq!(pointer_copy(s(&out.join("readme.txt")), s(&zip)).error, SEALED);
        assert_eq!(pointer_copy(s(&out.join("readme.txt")), s(&zip.join("docs"))).error, SEALED);
        assert_eq!(pointer_move(s(&out.join("readme.txt")), s(&zip.join("docs"))).error, SEALED);
        assert_eq!(pointer_mkdir(s(&zip.join("docs")), "new".into()).error, SEALED);
        assert_eq!(pointer_rename(s(&zip.join("readme.txt")), "x".into()).error, SEALED);
        assert_eq!(pointer_trash(s(&zip.join("readme.txt")), vec![s(&dir)]).error, SEALED);
        assert_eq!(pointer_save_text(s(&zip), "n.txt".into(), "x".into()).error, SEALED);
        assert_eq!(fs::read(&zip).unwrap(), before);
        assert!(out.join("readme.txt").exists());

        // All of it, into a folder named after the archive.
        let all = pointer_unpack_here(s(&zip), s(&dir));
        assert_eq!(all.error, "");
        assert_eq!(names(&dir.join("a")), vec!["docs", "empty", "readme.txt"]);
        assert!(dir.join("a/empty").is_dir());
        assert!(!dir.join("outside.txt").exists() && !dir.join("a/outside.txt").exists());
        assert_eq!(pointer_unpack_here(s(&zip), s(&dir)).error, "");
        assert!(dir.join("a (2)/docs/deep/Notes.md").exists());
        assert!(!pointer_unpack_here(s(&out.join("readme.txt")), s(&dir)).error.is_empty());
        // Taking it back sends the new folder to the trash.
        assert_eq!(pointer_undo(all.undo.unwrap(), vec![s(&dir)]).error, "");
        assert!(!dir.join("a").exists());

        // A cancelled one leaves nothing.
        pointer_job_cancel();
        assert_eq!(pointer_unpack_here(s(&zip), s(&out)).error, "Cancelled.");
        assert!(!out.join("a").exists());
        assert_eq!(pointer_copy(s(&zip.join("docs")), s(&dir)).error, "Cancelled.");
        assert!(!dir.join("docs").exists());
        pointer_job_start();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_tar_gz_is_walked_and_unpacked_too() {
        let _job = lock();
        let dir = scratch("tgz");
        let tgz = dir.join("b.tar.gz");
        let gz = flate2::write::GzEncoder::new(File::create(&tgz).unwrap(), flate2::Compression::fast());
        let mut tar = tar::Builder::new(gz);
        for (name, data) in [("./box/a.txt", "aaa"), ("box/inner/b.txt", "bb"), ("top.txt", "t")] {
            let mut head = tar::Header::new_gnu();
            head.set_size(data.len() as u64);
            head.set_mode(0o644);
            head.set_mtime(1_700_000_000);
            tar.append_data(&mut head, name, data.as_bytes()).unwrap();
        }
        // A link out of the archive: it is left out.
        let mut link = tar::Header::new_gnu();
        link.set_entry_type(tar::EntryType::Symlink);
        link.set_size(0);
        tar.append_link(&mut link, "box/passwd", "/etc/passwd").unwrap();
        tar.into_inner().unwrap().finish().unwrap();

        let top = pointer_list(s(&tgz), SortBy::Name, false, false);
        let got: Vec<&str> = top.entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!((got, top.error.as_str()), (vec!["box", "top.txt"], ""));
        assert_eq!(top.entries[1].modified, 1_700_000_000);
        let inner = pointer_list(s(&tgz.join("box")), SortBy::Name, false, false);
        let got: Vec<&str> = inner.entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(got, vec!["inner", "a.txt"]);

        assert_eq!(pointer_copy(s(&tgz.join("box/inner/b.txt")), s(&dir)).error, "");
        assert_eq!(fs::read_to_string(dir.join("b.txt")).unwrap(), "bb");
        // The copy has the time the archive gives the file.
        assert_eq!(epoch(fs::metadata(dir.join("b.txt")).unwrap().modified()), 1_700_000_000);
        assert_eq!(pointer_copy(s(&tgz.join("box")), s(&dir)).error, "");
        assert_eq!(fs::read_to_string(dir.join("box/inner/b.txt")).unwrap(), "bb");
        assert!(fs::symlink_metadata(dir.join("box/passwd")).is_err());
        assert_eq!(pointer_unpack_here(s(&tgz), s(&dir)).error, "");
        assert_eq!(names(&dir.join("b")), vec!["box", "top.txt"]);
        assert_eq!(pack_stem("b.tar.gz"), "b");
        assert_eq!(pack_stem(".zip"), ".zip");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_is_unpacked_once_to_be_looked_at() {
        let _job = lock();
        let dir = scratch("zip-view");
        let zip = dir.join("a.zip");
        zip_at(&zip);
        let cache = dir.join("cache");
        let first = pointer_unpack(s(&zip.join("docs/deep/Notes.md")), s(&cache));
        assert_eq!(first.error, "");
        assert!(first.path.starts_with(&s(&cache)) && first.path.ends_with("/Notes.md"));
        assert_eq!(fs::metadata(&first.path).unwrap().len(), 1800);
        // The second ask finds the copy: the job flag would stop a new one.
        pointer_job_cancel();
        assert_eq!(pointer_unpack(s(&zip.join("docs/deep/Notes.md")), s(&cache)), first);
        pointer_job_start();
        assert!(!pointer_unpack(s(&zip.join("docs")), s(&cache)).error.is_empty());
        assert!(!pointer_unpack(s(&dir.join("cache")), s(&cache)).error.is_empty());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn words_are_found_inside_files() {
        let _one = searching();
        let dir = scratch("grep");
        fs::create_dir_all(dir.join("sub")).unwrap();
        fs::create_dir_all(dir.join(".hid")).unwrap();
        fs::write(dir.join("a.txt"), "first\nthe Needle is here\nlast\n").unwrap();
        fs::write(dir.join("sub/README"), "nothing\n\nneedle again").unwrap();
        fs::write(dir.join("sub/photo.jpg"), "needle in a picture's bytes").unwrap();
        fs::write(dir.join("blob.bin"), b"\0\0needle").unwrap();
        fs::write(dir.join(".hid/c.txt"), "needle").unwrap();
        fs::write(dir.join("long.json"), format!("{}needle{}", "x".repeat(500), "y".repeat(500))).unwrap();
        fs::write(dir.join("norsk.txt"), "en\nto\n  Blåbær og RØMME\n").unwrap();

        let found = pointer_grep(s(&dir), " NEEDLE ".into(), false, 50);
        let mut got: Vec<(&str, u32, &str)> = found.iter().map(|e| (e.name.as_str(), e.line, e.note.as_str())).collect();
        got.sort();
        assert_eq!(got.len(), 3);
        assert_eq!(got[0], ("README", 3, "needle again"));
        assert_eq!(got[1], ("a.txt", 2, "the Needle is here"));
        // Of the one long line, the part with the words is shown.
        assert_eq!(got[2].1, 1);
        assert!(got[2].2.contains("needle") && got[2].2.chars().count() == 160);
        assert_eq!(pointer_grep(s(&dir), "needle".into(), true, 50).len(), 4);
        assert_eq!(pointer_grep(s(&dir), "needle".into(), true, 2).len(), 2);
        // Letters outside ASCII match with case ignored as well.
        let norsk = pointer_grep(s(&dir), "rømme".into(), false, 50);
        assert_eq!((norsk[0].line, norsk[0].note.as_str()), (3, "Blåbær og RØMME"));
        assert!(pointer_grep(s(&dir), "".into(), false, 50).is_empty());
        assert_eq!(first_hit(b"abc", ""), None);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_from_another_app_gets_a_safe_name_and_overwrites_nothing() {
        for (given, want) in [
            ("photo.jpg", "photo.jpg"),
            ("../../etc/passwd", "passwd"),
            ("C:\\Users\\x\\doc.pdf", "doc.pdf"),
            ("  .hidden  ", "hidden"),
            ("..", "shared"),
            ("", "shared"),
            ("a\nb\t.txt", "ab.txt"),
            ("dir/", "shared"),
        ] {
            assert_eq!(safe_name(given), want, "{:?}", given);
        }
        let long = format!("{}.jpeg", "æ".repeat(300));
        let cut = safe_name(&long);
        assert!(cut.len() <= 200 && cut.ends_with(".jpeg") && cut.starts_with('æ'));

        let dir = scratch("share");
        fs::write(dir.join("photo.jpg"), "old").unwrap();
        fs::write(dir.join(".photo.jpg.1.part"), "new").unwrap();
        let kept = pointer_keep(s(&dir.join(".photo.jpg.1.part")), "sub/../photo.jpg".into());
        assert_eq!(kept.error, "");
        assert_eq!(names(&dir), vec!["photo (2).jpg", "photo.jpg"]);
        assert_eq!(fs::read_to_string(dir.join("photo.jpg")).unwrap(), "old");
        assert_eq!(fs::read_to_string(dir.join("photo (2).jpg")).unwrap(), "new");
        assert!(!pointer_keep(s(&dir.join("gone.part")), "x".into()).error.is_empty());

        assert_eq!(pointer_save_text(s(&dir), "A page.txt".into(), "https://example.org".into()).error, "");
        assert_eq!(pointer_save_text(s(&dir), "A page.txt".into(), "second".into()).error, "");
        assert_eq!(fs::read_to_string(dir.join("A page.txt")).unwrap(), "https://example.org");
        assert_eq!(fs::read_to_string(dir.join("A page (2).txt")).unwrap(), "second");
        assert_eq!(names(&dir).len(), 4);
        assert!(!pointer_save_text(s(&dir.join("none")), "n.txt".into(), "x".into()).error.is_empty());
        fs::remove_dir_all(dir).unwrap();
    }
}
