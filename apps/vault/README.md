# vault

**[Download for Android (APK)](https://github.com/isene/nomad/releases/download/apk/vault.apk)** · [how to install](https://isene.org/nomad/#get)

Your passwords on the phone, from the same encrypted file as [vault](https://github.com/isene/vault) on the laptop.

- **Open** with the master password you use on the laptop.
- **Find:** type a few letters, and the list narrows. The list never shows a password.
- **Tap** an entry to copy its password. The clipboard is emptied 30 seconds later.
- **Tabs:** `Web` for entries with a site, `Other` for the rest, and one for each tag.
- **Open ›** shows every field of an entry. Change them, make a new password, or delete the entry.
- **New entry** adds one. The laptop has it at the next sync.
- **Lock:** the app locks when the screen goes dark, and after five minutes away.

## Setup

1. Make the file on the laptop first: run `vault` there once.
2. In Syncthing-Fork, accept the laptop's `gaze` folder and point it at `Documents/gaze`.
3. Tap *Allow file access* in the app.

The gaze browser on the phone reads the same file, so the two share one folder.

## Safety

- The file is sealed with ChaCha20-Poly1305, under a key made by Argon2id.
- The app has no network permission. Syncthing carries the sealed file.
- No screenshots, no picture in the list of recent apps, and no Android backup.
- The master password is never stored. The key is wiped when the app locks.
