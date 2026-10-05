//! lodeproxy: the front door of a Lodestar cluster.
//!
//! Players connect here. The proxy authenticates them, asks lodestar which
//! node should take them, and then relays their connection to that node.

pub mod auth;
pub mod config;
pub mod forwarding;
pub mod mc;
pub mod relay;
pub mod session;
pub mod star;
