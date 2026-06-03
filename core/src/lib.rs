// fe2o3-mobile-core
//
// Shared Rust core for the nomad mobile apps. Pure logic only. No Android
// APIs, no file I/O. The Kotlin shells wrap the platform side; this crate
// stays portable so the host-side tests below cover the same code that
// runs on the phone.

uniffi::setup_scaffolding!();

pub mod crypto;
pub mod hyperlist;
pub mod hyperlist_doc;
pub mod hyperlist_hl;
pub mod astro;
pub mod watchit;
pub mod amardice;
pub mod xrpn;
