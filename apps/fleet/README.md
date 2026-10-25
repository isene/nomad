# fleet

A buzz on the phone when a Claude Code session on the laptop finishes. The
phone twin of [fleet](https://github.com/isene/fleet).

- Three switches: "ETA work done" (work that was given an ETA has ended),
  "Every finished turn" (any answer a session finishes) and "Messages for
  the phone" (a laptop session wrote to the phone).
- One notice per session, replaced by its next one. A session that finishes
  twice within a minute buzzes once.
- The notice says which session, the first line of its answer, and the line
  that gave the ETA. A tap opens that session's answer.
- A session held by a permission dialog for a few seconds gets an orange
  notice, "waits for your yes", with what it wants to run. It buzzes with
  either of the first two switches on, even over that session's last notice.
  The list and the session's answer screen show what it waits for.
- Above the list: the week's share of the Claude limit, as the laptop's
  fleet header shows it and in its colours (green, then yellow from 50%,
  red from 80%), and when it resets.
- ⋮ beside the title opens About, with the version.
- A message for the phone gets a red notice with its first line. It buzzes
  when another message comes and goes once they are read. A tap opens the
  Claude app with "Check" typed in.
- The laptop fleet's session list: each session's state in fleet's
  colours, ETA, the count of its open items, and fleet's » after a
  session it wakes by itself. Sessions that are working or wait for you
  also show workspace, context size, model and the start of their last
  answer (your prompt while they work); the rest take one line. It is
  asked for only while the screen shows.
- Tap a session for its last answer in full, drawn as HyperList with
  the laptop's colours. Each open item has Yes, No, Done and Rec (sent
  as "as recommended"), with a line for words to go with it, and a box below takes words of your own.
  Send goes out as one message, as you would type it ("3 yes", "5 no:
  not today"). The items it answers leave the screen at once. One comes
  back only if the session's answer to it leaves it open.
- Hold a session to write it a message. fleet on the laptop wakes the
  session with it once the session waits at its prompt.
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
