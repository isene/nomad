<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# watchit

![version](https://img.shields.io/badge/version-0.2.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A TMDB movie & series browser — mobile companion to the Fe₂O₃ [watchit](https://github.com/isene/watchit) TUI. Part of the [nomad](../../) mobile suite.

</div>

`com.isene.watchit` · pairs with [watchit](https://github.com/isene/watchit)

## What it does

Decide what to watch from the couch.

- **Movies / Series** toggle (segmented control)
- Top-rated and popular lists from [TMDB](https://www.themoviedb.org); merge keeps your catalog growing
- Poster thumbnails captured at list-load (Coil, lazy + disk-cached)
- Filter sheet — rating, year range, genre include/exclude, sort
- **Wish** and **Dump** lists (dump hides a title from Browse)
- Detail screen — poster, cast, plot, runtime, content rating, seasons,
  streaming providers for your region, TMDB / IMDb links
- TMDB **search-to-add** for titles not in the charts
- **My own 1-10 rating** per title, tapped in on the detail screen and shown in
  gold beside TMDB's score in every list

## Ratings sync

The catalog, key and wish/dump stay on the phone. Ratings do not: point
Settings → **Ratings sync** at the Syncthing folder holding desktop watchit's
`~/.watchit/sync/`, and both ends see every score.

One file per device (`ratings-phone.json` here), because two writers on one
file is what makes Syncthing leave `.sync-conflict-` copies nobody reads. Each
device reads them all and writes only its own; newest timestamp per title wins,
and a cleared rating is a timestamped 0 rather than a deletion so the clear
survives the merge. Each entry carries its title and year as well as its id,
because the desktop catalog may still key a film by an IMDB tconst while this
app keys it by TMDB id — the title bridges the two.

Standalone otherwise: the phone keeps its own free TMDB v3 key, catalog, and lists.
All models, the TMDB JSON parsers, URL builders, and filter/sort logic live in
the Rust core (`core/src/watchit/`); Kotlin does the OkHttp fetches and Compose UI.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:watchit:assembleRelease
```

APK → `apps/watchit/build/outputs/apk/release/`. Sync and sideload; enter a
free TMDB v3 key in Settings, then fetch the lists.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
