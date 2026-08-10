<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# kastrup

![version](https://img.shields.io/badge/version-0.14.4-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![core](https://img.shields.io/badge/core-Rust%20%2F%20UniFFI-f74c00) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

Your Gmail inboxes and RSS feeds on the phone, sharing their decoders
with [kastrup](https://github.com/isene/kastrup) on the laptop and taking
its word for what has been read.
Part of the [nomad](../../) mobile suite.

</div>

`com.isene.mail` (module `apps/mail`) · pairs with [kastrup](https://github.com/isene/kastrup)

## What it does

- Every configured Gmail account's INBOX, last N days, newest first
- **RSS and Atom feeds** in the same list, parsed by
  [fe2o3-feed](https://github.com/isene/feed) — the same parser kastrup
  uses — with **Open** to read the whole thing in a browser
- **Discord channels** off the REST API with a bot token — no bridge, no
  laptop in the path
- **WhatsApp, Messenger, Instagram, SMS…** as the relay app on this same
  phone captures them, picked up the moment the app is opened
- **Discord DMs**, which belong to no channel, under **DMs** in the
  Discord group
- **Views**: named lists saved in Settings, at the top of the scope menu
- One **scope menu**: All, all mail or one mailbox, all feeds or one
  feed, all channels or one channel
- An **Unread** chip that hides everything already dealt with
- Bodies decoded by [fe2o3-mail](https://github.com/isene/mail) — the same
  crate kastrup uses, so quoted-printable, base64, RFC 2047 headers,
  multipart and HTML mail all come out the way they do on the desktop
- **Mark READ** / **Mark unread**, on this phone only
- Swipe a row (or **Remove** in a message) to take it off this phone —
  local only, the laptop keeps the mail
- **Tappable links** in the body
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
4. **Feeds and Discord** come across with the accounts: `mail-accounts`
   also writes `feeds.txt` from kastrup's RSS source and `discord.json`
   from its channel file and bot token, and one Import takes all three.
   Delete `mail-accounts.json` and `discord.json` afterwards — both hold
   credentials. The Settings field is editable — one
   `Title | https://…/feed.xml` per line, or a bare URL.
5. **Fetch every (min)** in Settings runs the same fetch in the
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
at all — a widget nobody looks at wakes nothing. The background fetch
keeps it current; without one it is as fresh as the last time the app
ran.

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

## Views

A named list, in Settings, one per line:

```
Dualog    | mail, match:dualog.com
Calc talk | rss:https://www.hpmuseum.org/forum/…, discord
Work      | mail:geir@passionfruits.net, discord:1288…
```

A name, a pipe, then the places to draw from — the same scope strings
the menu uses, OR'd together — plus an optional `match:` on sender,
recipient or subject.

The match is not a nicety. kastrup's own views filter on maildir folders
(`.AA.Customers.Dualog`), and the phone has none: it reads one INBOX per
account with nothing filed. Matching the address is how the same view is
expressed here.

## Chats

None of these can be read over an API. Meta exposes no way to read a
personal Messenger or Instagram conversation, and WhatsApp none at all.
What exists is the notification, and [relay](../relay/) already listens
for it — so this reads relay's own queue on the same device. No network,
no laptop, no Syncthing hop.

Relay writes captured messages twice: `inbound/` for the laptop and
`phone/` for this app. Two queues, because kastrup on the laptop
*deletes* from `inbound/` as it ingests and Syncthing carries that
deletion back — one queue with two readers starves the slower, which
would always be the phone.

Draining `phone/` is not part of the fetch. Those files are on this
phone's own disk, so picking them up costs one directory listing and no
radio at all; opening the app does it. Waiting fifteen minutes for a
network fetch to notice a file already on the disk is the one delay
here with no cause.

A Discord DM arrives this way too — the REST API a bot can read covers
channels, and a DM is in none of them. It lands under **DMs** in the
Discord group, which is defined as Discord from outside the channel
list rather than as a second source.

Worth being plain about what this is: a notification carries a sender and
a line of preview, and that is the whole message. No history, no thread,
nothing that never raised a notification, nothing while an app is muted.

## One list, many channels

A feed entry is not mail, but it is a message: something with a sender, a
time, a subject and a body, which is all the list, the widget and the
read state ever ask of it. So there is one record with a `source`, one
list, one scope — and the next channel is a fetcher and a menu entry
rather than another screen.

The scope is a single string (`""`, `mail`, `mail:<address>`, `rss`,
`rss:<url>`) rather than a source filter and an account filter, because
those were never independent: an account only means anything within
mail. As two fields, picking a mailbox silently hid every feed.

Feeds served over plain `http` are fetched over `https` instead. Android
forbids cleartext by default and a feed reader is not worth opting out
for; everything worth subscribing to redirects there anyway.

## Not yet

No push (the fetch is periodic, not instant) and no compose. Dualog
Workspace is the obvious next channel, and the only one that needs
something the phone cannot do alone — its OIDC login, or the laptop
relaying into the shared folder.

## License

Public domain. See [Unlicense](../../LICENSE).
