//! lodestar: the sync engine and coordinator of a Lodestar cluster.
//!
//! It tracks which nodes exist, decides which node each player is homed on and
//! which node simulates each region of each dimension, and relays replication
//! traffic between the nodes that share a chunk.

pub mod config;
pub mod server;
pub mod state;
pub mod storage;
pub mod zones;

pub mod world;
mod world_service;

mod world_database;
mod world_import;
mod world_pending;
mod world_worker;
