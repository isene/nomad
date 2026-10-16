# tock

The phone's calendars as a month, an agenda and a home-screen widget. The
phone twin of [tock](https://github.com/isene/tock).

- Shows the calendars the phone already keeps in sync (Google, Exchange
  and others), so it is up to date with the laptop off.
- Asks the accounts to fetch when it is opened, at most once in 15
  minutes. "Sync now" in the menu asks again.
- A month with week numbers and a dot per event on each day, in its
  calendar's colour. The days from the picked one on are listed below it.
- An event's own screen: time, place (a tap opens the map), notes with
  links that can be tapped, and who is invited. An invitation is answered
  there: yes, maybe or no, for one time or every time of a repeating one.
- New events, changes and deletes go to the phone's calendar store, and
  the account syncs them. A new event can repeat: every day, weekday,
  week, two weeks, month or year, with an end day or none. A repeating
  event can be deleted, one time or every time. Changing one is not built
  yet.
- Teams meeting: a switch on a new event hands its title, times and notes
  to the Teams app, which makes the meeting in the work calendar.
- Reminders come as notifications, from the alarms the phone's calendar
  store keeps.
- Widget: the coming 30 days as a list that scrolls. A tap opens the
  event, and the plus starts a new one. The wallpaper shows through it;
  "Widget background" in the menu puts a colour behind it, as solid as
  you like.
- Each calendar can be hidden, and can take the colour tock has for it on
  the laptop.
- About, in the menu, says how to use it.
- No network code, no sign-in, no Rust core. Nothing runs on a timer.

## Colours from the laptop

tock on a laptop gives a calendar one of a terminal's 256 colours, by
number. Under Calendars, a tap on a calendar's colour takes that number.

To set them all at once, pick a text file under "Colours from the laptop".
It has a line per calendar: the number, then the calendar's name or
address.

    214 alice@example.com
    37  Calendar
