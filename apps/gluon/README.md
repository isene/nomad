<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# gluon

**[Download for Android (APK)](https://github.com/isene/nomad/releases/download/apk/gluon.apk)** · [how to install](https://isene.org/nomad/#get)

![version](https://img.shields.io/badge/version-0.9.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Mail, calendar and tasks in one app, as
[gluon](https://github.com/isene/gluon) has them on the laptop.
Part of the [nomad](../../) mobile suite.

</div>

`com.isene.gluon` · pairs with [gluon](https://github.com/isene/gluon),
[kastrup](https://github.com/isene/kastrup), [tock](https://github.com/isene/tock)
and [scribe](https://github.com/isene/scribe)

## What it does

Four tabs at the bottom:

- **Home**: drafts waiting on the laptop, the unread messages, the coming
  week's events and the tasks, on one screen. Tap a message or an event to open it in its tab.
- **Messages**: your Gmail inboxes, feeds and chats, new mail sent straight
  from the phone, a search over all of Gmail, and instant mail with a
  notice per mail when you turn it on. See [docs/mail.md](docs/mail.md).
- **Calendar**: the phone's calendars as a month and an agenda, with
  reminders. See [docs/tock.md](docs/tock.md).
- **Tasks**: `~/.tasks/todo.hl` over Syncthing, with reminders for
  stamped items. See [docs/tasks.md](docs/tasks.md).

A tab keeps its place while you look at another: a half-written mail is
still there when you come back. Back on a tab's first screen goes Home.

Three home-screen widgets: Messages (the unread in the first three
views), the agenda and the tasks. A tap on one opens gluon on its tab.

## Moving from kastrup, tock and tasks

gluon replaced the three apps on 2026-10-29. It is a new app, so it
starts empty:

1. Install gluon.
2. Messages: tap **Load setup file** and pick `gluon-setup.json`, which
   the laptop's `gluon-setup` writes. Then ⋮ → Settings: pick the shared
   mail folder.
3. Calendar: let it read your calendars, then ⋮ → Calendars →
   **Colours from the laptop**.
4. Tasks: pick `todo.hl`. Android's picker hides a folder such as
   `.tasks`; open ☰ there and choose [pointer](../pointer/), which shows it.
5. Place the widgets again, then remove the three old apps.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:gluon:assembleRelease
```

APK → `apps/gluon/build/outputs/apk/release/`.

## License

[Unlicense](https://unlicense.org/), public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
