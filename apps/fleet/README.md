# fleet

A buzz on the phone when a Claude Code session on the laptop finishes. The
phone twin of [fleet](https://github.com/isene/fleet).

- Three switches: "ETA work done" (work that was given an ETA has ended),
  "Every finished turn" (any answer a session finishes) and "Messages for
  the phone" (a laptop session wrote to the phone).
- One notice per session, replaced by its next one. A session that finishes
  twice within a minute buzzes once.
- The notice says which session, the first line of its answer, and the line
  that gave the ETA. A tap opens the app at that session.
- A message for the phone gets a red notice with its first line. It buzzes
  when another message comes and goes once they are read. A tap opens the
  Claude app with "Check" typed in.
- The laptop fleet's session list: each session's state in fleet's
  colours, ETA, workspace, last activity, context size, model, last
  prompt and open items, and fleet's » after a session it wakes by itself. It is asked for only while the screen shows.
- Hold a session to write it a message. It reaches the session on its
  next prompt, or at once when fleet wakes it.
- Paste the address of your fleet connector once. It stays on the phone, and
  the app keeps no backup of it.
- With all switches off nothing runs. With one on, the app keeps one request
  open to the fleet server, screen on or off: about 45 short radio wakes an
  hour. It asks once to be left out of Android's battery saving, or Android
  delays the requests while the screen is off.
- Android shows a quiet "Listening" notice while the app asks. To hide it, turn
  the "Listening" category off in the app's notification settings, then switch
  a switch off and on. The buzzes have their own category and still ring.
- On a network fault it waits 30 seconds, then twice as long each time, up to
  10 minutes, or until the phone has a network again.
- No Rust core.
