//! Changes produced after a snapshot cut belong to the following transaction.
use std::{collections::HashMap, io};

use crate::world::{Request, WorldStore};
use bytes::Bytes;

type RecordKey = (String, String, i32, i32);
const MAX_BYTES: usize = 256 * 1024 * 1024;

#[derive(Default)]
pub(super) struct PendingChanges {
    records: HashMap<RecordKey, Request>,
    players: HashMap<u128, Bytes>,
    journal: Vec<(String, Bytes)>,
    bytes: usize,
}

impl PendingChanges {
    fn key(request: &Request) -> RecordKey {
        (
            request.dimension.clone(),
            request.kind.clone(),
            request.x,
            request.z,
        )
    }

    fn account(&mut self, added: usize, removed: usize) -> io::Result<()> {
        self.bytes = self.bytes - removed + added;
        if self.bytes > MAX_BYTES {
            return Err(io::Error::other("world save backlog exceeds 256 MiB"));
        }
        Ok(())
    }

    pub fn write(&mut self, request: Request) -> io::Result<()> {
        let size = request.data.len() + 256;
        let previous = self.records.insert(Self::key(&request), request);
        self.account(size, previous.map_or(0, |value| value.data.len() + 256))
    }

    pub fn player(&mut self, uuid: u128, data: Bytes) -> io::Result<()> {
        let size = data.len() + 64;
        let previous = self.players.insert(uuid, data);
        self.account(size, previous.map_or(0, |value| value.len() + 64))
    }

    pub fn append(&mut self, scope: String, data: Bytes) -> io::Result<()> {
        self.account(data.len() + scope.len() + 32, 0)?;
        self.journal.push((scope, data));
        Ok(())
    }

    pub fn read(&self, request: &Request) -> Option<Bytes> {
        self.records
            .get(&Self::key(request))
            .map(|value| value.data.clone())
    }

    pub fn journal(&self, store: &WorldStore, scope: &str, offset: u64) -> io::Result<Bytes> {
        let base_length = store.journal_length(scope)?;
        let mut page = store.journal(scope, offset, false)?.to_vec();
        if offset + (page.len() as u64) < base_length {
            return Ok(Bytes::from(page));
        }
        let mut position = base_length;
        for (name, data) in &self.journal {
            if name != scope {
                continue;
            }
            if position >= offset {
                if position != offset + page.len() as u64 {
                    return Err(io::Error::other("invalid pending journal offset"));
                }
                if page.len() + data.len() + 4 > lode_protocol::MAX_FRAME - 32 {
                    break;
                }
                page.extend_from_slice(&(data.len() as u32).to_be_bytes());
                page.extend_from_slice(data);
            }
            position += data.len() as u64 + 4;
        }
        Ok(Bytes::from(page))
    }

    pub fn apply(self, store: &WorldStore) -> io::Result<()> {
        for request in self.records.into_values() {
            store.write(&request)?;
        }
        for (uuid, data) in self.players {
            store.save_player(uuid, &data)?;
        }
        for (scope, data) in self.journal {
            store.append(&scope, &data)?;
        }
        Ok(())
    }
}
