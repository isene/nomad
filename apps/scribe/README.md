<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# scribe

![version](https://img.shields.io/badge/version-0.4.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Notes for the phone, with tags and pictures: plain Markdown files in a folder you share with your laptop. The touch companion to the Fe₂O₃ [scribe](https://github.com/isene/scribe) editor. Part of the [nomad](../../) mobile suite.

</div>

`com.isene.scribe` · pairs with [scribe](https://github.com/isene/scribe)

## What it does

Point it at a notes folder, shared with your laptop through Syncthing.
Every note is one text file, so nothing is locked inside the app.

- **The list**: each note with its first lines, its tags, its date and its
  first picture. Newest first or A–Z. Search looks in names and in the text.
- **Tags**: write `#idea` anywhere in a note. The tags of all notes sit in
  a row above the list; tap one to see only its notes. A note tagged
  `#pinned` stays at the top.
- **Pictures**: add one from the gallery or the camera. It is saved in
  `img/` in the notes folder, at most 2048 pixels wide or high, without
  the camera's record of place and time. The note gets a line like
  `![](img/20261005-101500.jpg)`. Pictures show above the text; tap one
  to see it large or to take it out of the note.
- **New note**: `+` opens an empty page. The note is saved under its first
  line, as `Shopping.md`.
- **The editor**: the text and nothing else, with find in the note (▲▼ to
  step) and a word count. A button puts a tag in at the cursor.
- Saves when you go back and when the app leaves the screen.
- Per note: rename, duplicate, delete (the ⋮ menu).
- Also lists `.hl` and `.txt` files, and opens a text file another app
  hands it.
- No permissions and no network. The folder is one you grant once.

On the laptop, [scribe](https://github.com/isene/scribe) opens the same
files and shows the pictures inside the text. `grep -l '#idea' ~/.notes/*`
finds a tag anywhere else.

Not there: a checklist is plain lines of `- [ ]` text that you edit, not
boxes to tap. Reminders and note colours do not exist.

## Moving from Google Keep

1. At [takeout.google.com](https://takeout.google.com) untick everything,
   tick Keep, and download the export.
2. On the laptop: `tools/keep-import takeout.zip ~/.notes`
   (`--check` first shows what it would do).

Each Keep note becomes a Markdown file named after its title. Labels become
tags, pictures go to `img/`, checklists become `- [ ]` lines, a pinned note
gets `#pinned`, archived notes go to `archive/`, the bin is left out. The
file time is the time the note was last edited, and a second run leaves the
notes it already made alone.

## Setup

1. Share a folder between laptop and phone with Syncthing, such as `~/.notes`.
2. Tap the folder icon and choose that folder on the phone.
3. Tap a note to edit it, or `+` to write a new one.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :apps:scribe:assembleRelease
```

APK → `apps/scribe/build/outputs/apk/release/`. Sync and sideload.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
