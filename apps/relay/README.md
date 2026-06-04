<div align="center">

<img src="src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="100" height="100">

# relay

![version](https://img.shields.io/badge/version-0.4.0-3ddc84) ![platform](https://img.shields.io/badge/platform-Android-3ddc84) ![shell](https://img.shields.io/badge/shell-Kotlin%20%2F%20Compose-7f52ff) ![license](https://img.shields.io/badge/license-Unlicense-green) ![Stay Amazing](https://img.shields.io/badge/Stay-Amazing-important)

A phone-side notification gateway that feeds [kastrup](https://github.com/isene/kastrup) — part of the [nomad](../../) mobile suite.

</div>

`com.isene.relay` · pairs with [kastrup](https://github.com/isene/kastrup)

## What it does

Bridges the chat apps that have no desktop API into kastrup on the laptop,
replacing the headless Firefox/Marionette session kastrup used to drive.

- **NotificationListenerService** captures incoming messages from an allowlist:
  WhatsApp, Messenger, Instagram, SMS (native), Discord, LinkedIn, and Reddit
- **Add app…** — pick any other installed chat app to relay it too (stored in
  on-device prefs, so app-specific identifiers never enter the repo)
- Writes each as uniform JSON into a [Syncthing](https://syncthing.net)-shared
  `inbound/` folder that kastrup drains
- **Direct Reply** — kastrup queues a reply into `outbox/`, relay fires the
  notification's RemoteInput action (or sends the SMS natively). The cached
  action is kept after the notification is dismissed, so a reply still lands
  once the chat has been swiped away; if the thread has no live action yet, the
  request is held and auto-sent the next time that thread notifies (24h TTL)
- **Delivery status** — every reply gets a truthful result written back to
  `outbox_status/<id>.json` (`sent` / `failed`), so kastrup can show whether the
  phone actually delivered it
- **Still-image media** — pulls the BigPicture preview off a photo notification
  and relays it as `media[]` (written before the JSON, atomically)
- De-dupes re-posted notifications; strips WhatsApp's group-summary noise and
  the " (N messages)" count suffix so a conversation stays one thread
- Event-driven throughout (`FileObserver` on `outbox/`, no polling)

Sideload-only (uses `MANAGE_EXTERNAL_STORAGE` for the synced gateway dir +
native SMS); not for Play.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_NDK_HOME="$HOME/.android-sdk/ndk/27.2.12479018"
./gradlew :apps:relay:assembleRelease
```

APK → `apps/relay/build/outputs/apk/release/`. Sync and sideload; grant
Notification access + All-files access, and point Syncthing-Fork at the
gateway folder shared with the laptop's `~/.kastrup/gateway/`.

## License

[Unlicense](https://unlicense.org/) — public domain. Part of [nomad](../../) · [isene.org/nomad](https://isene.org/nomad/)
