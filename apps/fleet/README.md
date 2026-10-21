# fleet

A buzz on the phone when a Claude Code session on the laptop finishes. The
phone twin of [fleet](https://github.com/isene/fleet).

- Two switches: "ETA work done" (work that was given an ETA has ended) and
  "Every finished turn" (any answer a session finishes).
- One notice per session, replaced by its next one. A session that finishes
  twice within a minute buzzes once.
- The notice says which session, the first line of its answer, and the line
  that gave the ETA.
- Paste the address of your fleet connector once. It stays on the phone, and
  the app keeps no backup of it.
- With both switches off nothing runs. With one on, the app keeps one request
  open to the fleet server, screen on or off: about 45 short radio wakes an
  hour. It asks once to be left out of Android's battery saving, or Android
  delays the requests while the screen is off.
- Android shows a quiet "Listening" notice while the app asks. To hide it, turn
  the "Listening" category off in the app's notification settings, then switch
  a switch off and on. The buzzes have their own category and still ring.
- On a network fault it waits 30 seconds, then twice as long each time, up to
  10 minutes, or until the phone has a network again.
- No Rust core.
