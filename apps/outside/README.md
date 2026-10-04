# outside

<img src="logo.svg" width="96" align="right" alt="outside">

Three weather forecasts for one place, side by side: Yr, Storm and GFS.
Where they agree you can trust the day. Where they differ you see it at
once.

- Up to sixteen days ahead; tap a day for its hours.
- A dot per day: green when the forecasts agree, amber when they differ,
  red when they disagree. The open day says on what ("Rain: 0 to 14 mm").
- A score from 0 to 10 for being outside, the day's best stretch
  ("13 to 17"), and a star on the best of the next seven days.
- Sunrise and sunset for each day.
- Search for a place, follow the phone's position, save the places you
  come back to.
- The last forecast stays on the phone, so the app opens with numbers
  when there is no network.

## Sources

| Column | From | Reach |
|---|---|---|
| Yr | MET Norway's `locationforecast` | about 10 days, hourly for the first two |
| Storm | TV 2's weather pages | 15 days, hourly for the first eleven |
| GFS | NOAA's model, through Open-Meteo | 16 days, hourly |

All three answer for any point on Earth, with no key and no account.
Place search uses Open-Meteo's geocoder (GeoNames data).

Storm has no public service for this. The app asks the same server
TV 2's own page asks, so that column can stop working the day TV 2
changes its page. The other two columns keep working when it does.

## Battery and privacy

- The app fetches when you open it, and only what is older than half an
  hour. Nothing runs in the background.
- The phone's position is rounded to about a kilometre before it leaves
  the phone. The app asks for coarse location only.
- No account, no tracking, no ads.

## Code

- `core/src/outside.rs` builds the requests, reads the three answers and
  lays out the days and hours. It is pure Rust with tests.
- The Kotlin side fetches, keeps the answers in the cache folder and
  draws the screens.
