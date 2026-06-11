<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# books

![version](https://img.shields.io/badge/version-0.1.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

**The library on your phone.** Read-only companion to the
[library](https://github.com/isene/library) tool — part of the
[nomad](../../) mobile suite.

</div>

The mobile reader for the [`library`](https://github.com/isene/library) tool: a
generative personal library where, on the laptop, you curate a shelf of books
that *should* exist and have Claude write the ones you grab (or fetch real ones
from legal sources). **books** is the read-only half — it shows the books you
have already made and lets you read them anywhere, offline.

## What it does

- Reads the synced `~/.library` folder over the Storage Access Framework — pick
  it once, nothing leaves the device.
- Shows **only books that have been written** (grabbed on the laptop), grouped
  by shelf. Conjured books in parchment, real books in gold; starred books
  carry a star.
- Search across titles, authors, shelves and tags.
- A full-screen reader: chapter headings, prose, pull-quotes, and the inline
  figures (`books/<id>/img/figN.png`) drawn for the book. Adjustable text size,
  reading-progress percentage.

## How it stays cheap

- Pure Kotlin/Compose, no Rust core: `catalog.json` and the line-oriented
  `book.md` are trivial to parse with `org.json` and a line walk.
- Read-only. No network, no background work, no polling — it re-scans the
  folder only when you open or return to the app.
- One SAF listing per book open resolves every figure; nothing per frame.

## Data layout it expects

```
~/.library/
├── catalog.json                    every book idea; books reads the written ones
└── books/<id>/
    ├── book.md                     Markdown, with [[FIG n: caption]] markers
    └── img/figN.png                the figures
```

Get the folder onto the phone with a Syncthing folder pointing at `~/.library`
(read-only on the phone is fine — books never writes).

## Build

```bash
./gradlew :apps:books:assembleRelease
```

Sideload the signed APK. Public domain (Unlicense) — clone it and prompt the
changes that fit *your* shelves.
