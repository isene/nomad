// astro: shared logic for the mobile astronomy app (nomad app #4).
//
// All pure compute — ephemeris (via the dependency-free `orbit` crate),
// weather/events/APOD parsing, and gear optics. Network I/O lives in the
// Kotlin shell (OkHttp); this module only parses bodies and computes. Keeps
// the core free of C/TLS deps so it cross-compiles cleanly.

pub mod ephem;
pub mod events;
pub mod gear;
pub mod images;
pub mod weather;
