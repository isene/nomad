<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# kastrup

![version](https://img.shields.io/badge/version-0.1.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Your Gmail inboxes on the phone, sharing one decoder with
[kastrup](https://github.com/isene/kastrup) on the laptop and taking its
word for what has been read.
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
- **Attachments** listed under the headers; tap one to open it in
  whatever app handles the type
- A **Removed** chip to look through what a swipe hid, and swipe there to
  put one back
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
| Anything done here | Stays here |

The arrow points one way. This phone **writes nothing** into the shared
folder — it reads the laptop's `mail-read-*.json` (keyed by RFC822
`Message-ID`, newest timestamp wins, merged by the Rust core) and keeps
its own marks in its own prefs, where they override the laptop's for
display and reach nobody.

So marking read, marking unread, and removing are all free: none of them
can cost you anything on the laptop, which is the machine that has the
mail.

The server's `\Seen` flag is not consulted. The laptop's fetcher marks
everything seen as it delivers, so the server has no opinion worth
having.

Removing is the one thing that stays put. A swipe writes a Message-ID to
this phone's own prefs and nowhere else: no shared file, no IMAP delete,
no server round trip. Clearing the phone's list must never cost you the
mail. Nothing is deleted: a swipe adds a Message-ID to a local list, so
the **Removed** chip can show you exactly what is on it and a swipe there
takes one back off. The status line offers the last one back with a tap,
and the list is pruned to the fetch window on every sync so it cannot
grow without bound.

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
4. **Fetch every (min)** in Settings runs the same fetch in the
   background. 15 minutes by default, 0 turns it off.

## Attachments

Listed under the headers once the message is open, with name and size.
Tapping one writes it to the app's cache and hands a one-read URI to
whatever app can show it — the chooser's own share sheet is where you
save it somewhere permanent.

The listing is cheap and the bytes are not, so they are separate calls:
drawing a row of nine photos never decodes a megabyte, and only the one
you tap gets written. Cache, because the message is still on the phone
and the file can always be written again.

## The widget

Unread count and senders on the home screen. It reads a small summary
file the app writes when the count moves, and declares no update period
at all — a widget nobody looks at wakes nothing. The flip side: with no
push yet, it is as fresh as the last time the app ran.

## Background fetch

WorkManager, not a timer: it batches with whatever else the phone was
going to wake for, respects Doze and App Standby, and survives reboot. A
network constraint means a fetch is never attempted with the radio off,
which is the expensive way to fail.

A fetch asks only for UIDs above the last one it saw, and asks every
account at once. The first fetch reads the whole window; after that it
is a handful of messages over three parallel sessions rather than a
month of envelopes over three sequential ones. If the server renumbers
(UIDVALIDITY changes) it falls back to the full window by itself.

Fifteen minutes is Android's floor for periodic work. Real push would
mean holding a socket open all day — a different trade, and not this one.

## Not yet

No push (the fetch is periodic, not instant) and no compose.

## License

Public domain. See [Unlicense](../../LICENSE).
