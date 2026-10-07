# gaze

The laptop browser's best parts on the phone, around Android's own WebView.

- **Tabs** that come back after a restart, each with the pages it came through. Only the tab you look at loads.
- **Back** walks the pages of the tab, one at a time, to the first. One more press leaves gaze.
- **Hold a link** to open it in a new tab behind this one, or to copy its address.
- **Private tabs:** *New private tab* in the menu, or hold a link. Marked ⊘. A private tab is in no history, is not kept over a restart, and does not see the cookies of your other tabs. A link opened from one is private too. When the last one closes, their cookies, cache and site data go. If Android stops gaze first, they go at the next start. No login is filled or offered for saving unless you pick *Fill password*. The site and your network still see your address.
- **Tabs sent across:** the menu's *Send to laptop* opens the page in gaze on the laptop, and `:send` there opens it here.
- **Passwords:** the laptop's encrypted file, the same master password. Login pages are filled, and a new or changed password is offered for saving.
- **Bookmarks** shared with the laptop; the address line suggests them first, then the pages you visit most.
- **Ad blocking** from Steven Black's hosts list, as on the laptop. The page you asked for is never blocked.
- **Dark pages:** each site is asked for its dark style, and a site with none is turned around. *Dark here* flips it for one site.
- **Ask Claude** hands the page, its title, address and text, to the Claude app. You ask there, on your own plan; no API key.
- **Pull down** at the top of a page to reload it. A map or a scrolling panel keeps its drag.
- **Share** from the menu sends the page to any app. Links from other apps open here, and links shared to gaze open too.

## Setup

1. Grant *All files access* from the settings screen.
2. In Syncthing-Fork, accept the laptop's `gaze` folder and point it at `Documents/gaze` (the default in the settings).
3. Install the Claude app to ask about pages.

Everything else is local: the history, the per-site dark choices, the ad list, the settings.
