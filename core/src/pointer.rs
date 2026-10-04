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

use std::cmp::Ordering;
use std::collections::VecDeque;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering as Atomic};
use std::time::{SystemTime, UNIX_EPOCH};

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
    /// Seconds since 1970.
    pub modified: i64,
    pub hidden: bool,
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
        Err(e) => return Listing { entries: Vec::new(), error: plain(&e) },
    };
    let mut entries: Vec<Entry> = read
        .flatten()
        .filter_map(|d| entry_of(&d.path()))
        .filter(|e| (hidden || !e.hidden) && e.name != TRASH)
        .collect();
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
    Listing { entries, error: String::new() }
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

/// One file, written under a hidden name first and given its real name
/// only when all of it is there. Keeps the time the file was changed.
fn copy_file(src: &Path, dest: &Path, how: &mut Copier) -> io::Result<()> {
    let part = dest.with_file_name(format!(".{}.part", name_of(dest)));
    let mut run = || -> io::Result<()> {
        let mut from = File::open(src)?;
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
        if let Ok(t) = from.metadata().and_then(|m| m.modified()) {
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
            return Err("It is not there any more.".to_string());
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
        let (f, b) = tree_stats(Path::new(p));
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
}
