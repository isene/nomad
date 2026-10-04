# pointer

<img src="logo.svg" width="96" align="right" alt="pointer">

A file manager for the phone, after the
[pointer](https://github.com/isene/pointer) of the terminal. Tag what you
want, walk to where it should go, and put it there. Every step can be
taken back.

- **Tags that stay.** Tap an item's picture, or hold the row, to tag it.
  The tags stay while you walk to another folder. There you press
  *Copy here* or *Move here*.
- **Nothing is overwritten.** A copy that meets a taken name gets a new
  one: `notes (2).txt`.
- **Nothing is deleted outright.** Delete moves the item to the trash,
  where it waits until you empty it. *Put back* returns it to its folder.
- **Undo.** Copy, move, rename, delete and new folder can each be taken
  back, from the bar that tells you what happened or from the menu.
- **Marks.** Star a folder and it sits in the row on top, one tap away.
  Hold a mark and drag it to change the order.
- **Save from any app.** Share a file from another app and pick
  *Save to folder*. Your marks come up; a tap on one saves the file there.
- **Tabs.** Keep several folders open and jump between them. They are
  there again the next time you open the app.
- **Archives open as folders.** Walk into a zip, jar, tar or tar.gz file,
  look at what is in it, and copy out what you need. *Unpack here* takes
  all of it out.
- **Search.** Typing narrows the folder on screen. One more tap searches
  every folder below it, by name or for words inside the text files.
- **Pictures and text open in the app.** Swipe through the pictures of a
  folder, double-tap to zoom. Everything else opens in the app the phone
  picks for it; an APK goes to the installer. Pictures and videos show
  what is in them right in the list.
- **Places.** The phone's storage and the SD card with their free space,
  the folders Syncthing shares, and the folders you last worked in. The
  star on a row marks that place.
- Sort by name, size, date or kind; show hidden files; see the size of
  a folder with all in it; share files to any app.

## What it may and may not do

- It asks for access to all files, by hand, in Android's settings. A
  file manager cannot work without it.
- It has **no network permission**. Nothing it reads can leave the phone
  through it.
- No account, no ads, no tracking, nothing running in the background.
- Android itself keeps `Android/data` and `Android/obb` closed to every
  app, this one too.
- *Save to folder* writes nothing before you tap a folder, so no other
  app can put a file on the phone through it unseen.
- An archive is read and never changed. A file in it whose name points
  outside the folder you unpack to is left out.

## How it keeps your files safe

- A file is copied under a hidden name and gets its real name only when
  all of it has arrived. A copy that fails or is stopped leaves nothing
  behind.
- A move to another volume (phone to SD card) is a copy first. The
  original goes only after the copy is on the disk with every file and
  every byte counted.
- The trash is a folder named `.pointer-trash` on the volume the item
  was on, so a delete is a rename and takes no time. *Empty* is the one
  step that cannot be taken back, and it asks first.

## Code

- `core/src/pointer.rs` does the file work: listing, sorting, search,
  copy, move, rename, trash, restore, undo and reading archives. Its
  tests run on real folders on the laptop, one of them across two file
  systems.
- The Kotlin side draws the screens, reads the volumes and hands a file
  to and from another app.

Not built yet: SFTP to a server, bulk rename, making archives, and the
7z, rar, bz2 and xz kinds.
