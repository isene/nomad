# tasks in gluon

The Tasks tab of [gluon](../README.md).

## What it does

Edits the same `~/.tasks/todo.hl` 2-level [HyperList](https://isene.org/hyperlist/)
that scribe edits on the laptop and kastrup's `z` triage appends to. The list
rides over [Syncthing](https://syncthing.net), so a change on either device
shows up on the other — no Google account, no cloud middleman.

- Two-level hyperlist (categories → items), parsed and serialized by the Rust core
- Add / edit / complete / delete items; categories collapse
- **Reminders**: "Add a reminder" under an item's text picks a day and a time,
  and the phone notifies you then. It is written into the list the HyperList
  way, `2026-10-17 09.00: Call Alice`, so a stamp typed in scribe works too
- **Glance home-screen widget** showing the first items across categories.
  The wallpaper shows through it; "Widget background" in the menu puts a
  colour behind it, as solid as you like. Each line has its category's
  colour; "Widget text" sets the colours, the text size, and whether the
  category's name stands in front. Type six hex digits for a colour, or
  tap its square to pick one
- Storage Access Framework: point it at the synced `~/.tasks/` folder
- About screen; Material 3; adaptive icon

The hyperlist parser, serializer, and transforms live in the shared Rust core
(`core/src/hyperlist.rs`); Kotlin owns SAF I/O, the Compose UI, and the widget.
