// XRPN mobile core: an HP-41CX-style RPN scientific calculator, ported from
// the desktop XRPN (Ruby) so the stack model, command semantics, and number
// formatting match exactly. Pure logic — the Kotlin shell holds the CalcState
// Record and forwards key/command tokens; every entry point returns new state.
//
// Scope (v1): the calculator. Stack (X/Y/Z/T) + Last X + alpha + numbered
// registers + flags + display modes + the full math/trig/log/stats/base/HMS/
// polar command set. The FOCAL program engine (lbl/gto/xeq/isg/dse/…) is out
// of scope for v1.

mod format;
mod engine;

pub use engine::*;
