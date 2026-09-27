# gaze

The laptop browser's best parts on the phone, around Android's own WebView.

- **Tabs** that come back after a restart. Only the tab you look at loads.
- **Tabs sent across:** the menu's *Send to laptop* opens the page in gaze on the laptop, and `:send` there opens it here.
- **Passwords:** the laptop's encrypted file, the same master password. Login pages are filled, and a new or changed password is offered for saving.
- **Bookmarks** shared with the laptop; the address line suggests them first, then the pages you visit most.
- **Ad blocking** from Steven Black's hosts list, as on the laptop. The page you asked for is never blocked.
- **Dark pages:** each site is asked for its dark style, and a site with none is turned around. *Dark here* flips it for one site.
- **Ask Claude** about the page, with follow-up questions. It needs your own API key from console.anthropic.com, set in the settings.
- Opens links from other apps, and takes links shared to it.

## Setup

1. Grant *All files access* from the settings screen.
2. In Syncthing-Fork, accept the laptop's `gaze` folder and point it at `Documents/gaze` (the default in the settings).
3. Put your API key in the settings to ask Claude.

Everything else is local: the history, the per-site dark choices, the ad list, the settings.
