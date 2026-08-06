<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# kastrup

![version](https://img.shields.io/badge/version-0.1.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Your Gmail inboxes on the phone, sharing one decoder and one notion of
"read" with [kastrup](https://github.com/isene/kastrup) on the laptop.
Part of the [nomad](../../) mobile suite.

</div>

`com.isene.mail` (module `apps/mail`) · pairs with [kastrup](https://github.com/isene/kastrup)

## What it does

- Every configured Gmail account's INBOX, last N days, newest first
- Unread and per-account filter chips
- Bodies decoded by [fe2o3-mail](https://github.com/isene/mail) — the same
  crate kastrup uses, so quoted-printable, base64, RFC 2047 headers,
  multipart and HTML mail all come out the way they do on the desktop
- An explicit **Mark READ** that reaches the laptop
- Swipe a row (or **Remove** in a message) to take it off this phone —
  local only, the laptop keeps the mail
- An **Unread** chip that hides everything already dealt with
- **Mark all read** and **Remove all** in the ⋮ menu, acting on what the
  list is showing — so a filter narrows them
- A **home-screen widget**: the same unread list at a glance, through the
  same account filter, tap to open

## Read state

The laptop is the authoritative device. That is not a preference setting;
it is what each side *writes*:

| Event | Effect |
|---|---|
| Read on the laptop | Read here too |
| Deleted on the laptop | Read here too |
| Merely opened here | Nothing at all |
| **Mark READ** tapped here | Read on the laptop too |

Storage is one file per device (`mail-read-<device>.json`) in a folder
shared over Syncthing, keyed by RFC822 `Message-ID`. Each device writes
only its own file and reads them all, so Syncthing never has two writers
to leave a `.sync-conflict-` copy of. Newest timestamp wins, and the
merge lives in the Rust core so both ends resolve a disagreement
identically.

The server's `\Seen` flag is not consulted. The laptop's fetcher marks
everything seen as it delivers, so the server has no opinion worth
having.

Removing is the one thing that stays put. A swipe writes a Message-ID to
this phone's own prefs and nowhere else: no shared file, no IMAP delete,
no server round trip. Clearing the phone's list must never cost you the
mail. The status line offers it back with a tap, and the list is pruned
to the fetch window on every sync so it cannot grow without bound.

## Setup

1. On the laptop, run `mail-accounts`. It gathers every Gmail account's
   client id, secret and refresh token into `mail-accounts.json` in the
   folder Syncthing shares with the phone.
2. **⋮ → Settings → Pick the shared folder** — the Syncthing folder
   carrying kastrup's `~/.kastrup/sync`. Then **Import
   mail-accounts.json** (drop that file in the folder first). Delete it
   afterwards: a refresh token is the whole credential and has no
   business sitting in a synced folder. The field is editable, if you
   would rather type it.
3. **↻** fetches headers. Bodies download when you open a message, not
   before: a month of full bodies is minutes of radio for mail that
   mostly never gets read on a phone.

## The widget

Unread count and senders on the home screen. It reads a small summary
file the app writes when the count moves, and declares no update period
at all — a widget nobody looks at wakes nothing. The flip side: with no
push yet, it is as fresh as the last time the app ran.

## Not yet

No push, no compose. Both wait until the reading half has earned its
keep.

## License

Public domain. See [Unlicense](../../LICENSE).
