# orrery

An astronomy clock for the phone, made to look like an old astronomical clock in brass and blue enamel.
It belongs to the [Fe₂O₃ suite](https://isene.org/fe2o3/).

## The dial

- A 24-hour dial with noon at the top, a sun hand for the hour, and a minute and a second hand.
- The ring of hours is the sky of the next 24 hours, from blue day to deep night.
- The twilights show as bands of colour: civil, nautical and astronomical.
- A line and a small sun mark sunrise and sunset; a crescent marks moonrise and moonset.
- The thin ring inside it is the cloud forecast: dark is clear, pale is overcast.
- Six tracks show the Moon, Mercury, Venus, Mars, Jupiter and Saturn while they are up.
  A track is bright where the body is high in a dark sky, and its sign sits at its highest point.
- The zodiac ring turns with the sky. The sun stays under the hour hand, and a body near the top is due south.
- The Moon has a window of its own, lit for tonight's phase, with the part lit and its age in days.
- The date and the sidereal time are at the bottom.

## Below the dial

- Next: the next rise, set or twilight, with a countdown, and the ones after it.
- Tonight: the hours of full dark, how much of it has the Moon down, and the clouds then.
- Moon: the phase, its rise and set, and the next full and new moon.
- Planets: for each, where it is now, how bright, in which constellation, and when it rises, is highest and sets.
- Ahead: the next two months of moon phases, eclipses, meteor showers, seasons, and planets close together.

The astronomy is computed on the phone by [orbit](https://github.com/isene/orbit), with no network.
The app fetches only the cloud forecast from MET Norway, once an hour while it is open.
It shows Oslo until it may use the phone's location.
The second hand moves eight steps a second, like a fine watch, and everything stops while the app is not on screen.
