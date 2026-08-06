<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# mail

![version](https://img.shields.io/badge/version-0.1.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Your Gmail inboxes on the phone, sharing one decoder and one notion of
"read" with [kastrup](https://github.com/isene/kastrup) on the laptop.
Part of the [nomad](../../) mobile suite.

</div>

`com.isene.mail` · pairs with [kastrup](https://github.com/isene/kastrup)

## What it does

- Every configured Gmail account's INBOX, last N days, newest first
- Unread and per-account filter chips
- Bodies decoded by [fe2o3-mail](https://github.com/isene/mail) — the same
  crate kastrup uses, so quoted-printable, base64, RFC 2047 headers,
  multipart and HTML mail all come out the way they do on the desktop
- An explicit **Mark READ** that reaches the laptop

## Read state

The laptop is the authoritative device. That is not a preference setting;
it is what each side *writes*:

| Event | Effect |
|---|---|
| Read on the laptop | Read here too |
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

## Setup

1. **⋮ → Settings → Accounts JSON**. A list of
   `{address, client_id, client_secret, refresh_token}`, one per Gmail
   account. It stays in this app's private prefs, never in the shared
   folder.
2. **Pick the shared folder** — the Syncthing folder the laptop writes
   its read state into.
3. **↻** fetches headers. Bodies download when you open a message, not
   before: a month of full bodies is minutes of radio for mail that
   mostly never gets read on a phone.

## Not yet

No push, no compose. Both wait until the reading half has earned its
keep.

## License

Public domain. See [Unlicense](../../LICENSE).
