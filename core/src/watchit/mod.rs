// watchit: movie/series browser core, ported from the desktop watchit TUI.
// Pure logic — data models, TMDB JSON parsing, URL building, and the
// filter/sort/genre logic. Kotlin owns the HTTP (OkHttp), poster loading
// (Coil), local persistence, and Compose UI. TMDB numeric ids are the
// primary key (matching desktop watchit ≥ 0.2).

pub mod models;
pub mod tmdb;
pub mod filter;
