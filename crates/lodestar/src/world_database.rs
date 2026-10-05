//! One durable transaction contains every participant's inventory and world records.
use std::{cell::Cell, io, path::Path};

use bytes::Bytes;
use rusqlite::{Connection, OptionalExtension, params};

use crate::world::Request;

pub struct WorldStore {
    database: Connection,
    reader: Connection,
    revision: Cell<u64>,
}

fn error(error: rusqlite::Error) -> io::Error {
    io::Error::other(error)
}

impl WorldStore {
    pub fn open(root: impl AsRef<Path>) -> io::Result<Self> {
        std::fs::create_dir_all(&root)?;
        let database = Connection::open(root.as_ref().join("world.sqlite3")).map_err(error)?;
        database.execute_batch(
            "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
             CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
             INSERT OR IGNORE INTO metadata VALUES ('revision', 0);
             INSERT OR IGNORE INTO metadata VALUES ('epoch', 0);
             CREATE TABLE IF NOT EXISTS records (
               dimension TEXT NOT NULL, kind TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL,
               data BLOB NOT NULL, revision INTEGER NOT NULL,
               PRIMARY KEY(dimension,kind,x,z));
             CREATE TABLE IF NOT EXISTS players (uuid BLOB PRIMARY KEY, data BLOB NOT NULL, revision INTEGER NOT NULL);
             CREATE TABLE IF NOT EXISTS journal (
               scope TEXT NOT NULL, offset INTEGER NOT NULL, data BLOB NOT NULL, revision INTEGER NOT NULL,
               PRIMARY KEY(scope,offset));
             CREATE TABLE IF NOT EXISTS commits (revision INTEGER PRIMARY KEY, committed_at TEXT NOT NULL);
             BEGIN IMMEDIATE;"
        ).map_err(error)?;
        let reader = Connection::open_with_flags(
            root.as_ref().join("world.sqlite3"),
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .map_err(error)?;
        let revision = database
            .query_row(
                "SELECT value FROM metadata WHERE key='revision'",
                [],
                |row| row.get(0),
            )
            .map_err(error)?;
        let store = Self {
            database,
            reader,
            revision: Cell::new(revision),
        };
        crate::world_import::import(&store, root.as_ref())?;
        store
            .database
            .execute_batch(
                "UPDATE metadata SET value=value+1 WHERE key='epoch'; COMMIT; BEGIN IMMEDIATE;",
            )
            .map_err(error)?;
        Ok(store)
    }

    pub fn epoch(&self) -> io::Result<u64> {
        self.database
            .query_row("SELECT value FROM metadata WHERE key='epoch'", [], |row| {
                row.get(0)
            })
            .map_err(error)
    }

    pub fn revision(&self) -> io::Result<u64> {
        Ok(self.revision.get())
    }

    pub fn commit(&self, revision: u64) -> io::Result<()> {
        if revision != self.revision()? + 1 {
            return Err(io::Error::other(
                "world commit has a stale or skipped revision",
            ));
        }
        self.database
            .execute(
                "UPDATE metadata SET value=?1 WHERE key='revision'",
                [revision],
            )
            .map_err(error)?;
        self.database
            .execute(
                "INSERT INTO commits VALUES (?1, strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
                [revision],
            )
            .map_err(error)?;
        // Simulation can continue, but this revision is durable only after COMMIT succeeds.
        self.database
            .execute_batch("COMMIT; BEGIN IMMEDIATE;")
            .map_err(error)?;
        self.revision.set(revision);
        Ok(())
    }

    pub fn read(&self, request: &Request, committed: bool) -> io::Result<Option<Bytes>> {
        let database = if committed {
            &self.reader
        } else {
            &self.database
        };
        database
            .prepare_cached(
                "SELECT data FROM records WHERE dimension=?1 AND kind=?2 AND x=?3 AND z=?4",
            )
            .map_err(error)?
            .query_row(
                params![request.dimension, request.kind, request.x, request.z],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map(|data| data.map(Bytes::from))
            .map_err(error)
    }

    pub fn write(&self, request: &Request) -> io::Result<()> {
        self.database.prepare_cached(
            "INSERT INTO records VALUES (?1,?2,?3,?4,?5,?6)
             ON CONFLICT(dimension,kind,x,z) DO UPDATE SET data=excluded.data,revision=excluded.revision
             WHERE records.data != excluded.data",
        ).map_err(error)?.execute(
            params![request.dimension, request.kind, request.x, request.z, request.data.as_ref(), self.revision()? + 1],
        ).map_err(error)?;
        Ok(())
    }

    pub fn save_player(&self, uuid: u128, data: &[u8]) -> io::Result<()> {
        self.database.prepare_cached(
            "INSERT INTO players VALUES (?1,?2,?3) ON CONFLICT(uuid)
             DO UPDATE SET data=excluded.data,revision=excluded.revision WHERE players.data != excluded.data",
        ).map_err(error)?.execute(
            params![uuid.to_be_bytes().as_slice(), data, self.revision()? + 1],
        ).map_err(error)?;
        Ok(())
    }

    pub fn players(&self) -> io::Result<Vec<(u128, Bytes)>> {
        let mut statement = self
            .database
            .prepare("SELECT uuid,data FROM players")
            .map_err(error)?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
            })
            .map_err(error)?;
        rows.map(|row| {
            let (uuid, data) = row.map_err(error)?;
            let uuid = uuid
                .try_into()
                .map_err(|_| io::Error::other("invalid stored player UUID"))?;
            Ok((u128::from_be_bytes(uuid), Bytes::from(data)))
        })
        .collect()
    }

    pub fn append(&self, scope: &str, payload: &[u8]) -> io::Result<()> {
        self.database.prepare_cached(
            "INSERT INTO journal VALUES (?1,COALESCE((SELECT offset+length(data)+4 FROM journal WHERE scope=?1 ORDER BY offset DESC LIMIT 1),0),?2,?3)",
        ).map_err(error)?.execute(
            params![scope, payload, self.revision()? + 1],
        ).map_err(error)?;
        Ok(())
    }

    pub fn journal(&self, scope: &str, offset: u64, committed: bool) -> io::Result<Bytes> {
        let database = if committed {
            &self.reader
        } else {
            &self.database
        };
        let mut statement = database
            .prepare_cached(
                "SELECT offset,data FROM journal WHERE scope=?1 AND offset>=?2 ORDER BY offset",
            )
            .map_err(error)?;
        let rows = statement
            .query_map(params![scope, offset], |row| {
                Ok((row.get::<_, u64>(0)?, row.get::<_, Vec<u8>>(1)?))
            })
            .map_err(error)?;
        let mut page = Vec::new();
        for row in rows {
            let (position, data) = row.map_err(error)?;
            if position != offset + page.len() as u64 {
                return Err(io::Error::other("invalid journal offset"));
            }
            if page.len() + data.len() + 4 > lode_protocol::MAX_FRAME - 32 {
                break;
            }
            page.extend_from_slice(&(data.len() as u32).to_be_bytes());
            page.extend(data);
        }
        Ok(Bytes::from(page))
    }

    pub fn journal_length(&self, scope: &str) -> io::Result<u64> {
        self.database.query_row(
            "SELECT COALESCE((SELECT offset+length(data)+4 FROM journal WHERE scope=?1 ORDER BY offset DESC LIMIT 1),0)",
            [scope], |row| row.get(0),
        ).map_err(error)
    }

    pub(crate) fn imported(&self) -> io::Result<bool> {
        self.database
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM metadata WHERE key='imported')",
                [],
                |row| row.get(0),
            )
            .map_err(error)
    }

    pub(crate) fn finish_import(&self) -> io::Result<()> {
        self.database.execute_batch("INSERT INTO metadata VALUES ('imported',1); UPDATE records SET revision=0; UPDATE players SET revision=0; UPDATE journal SET revision=0; COMMIT; BEGIN IMMEDIATE;").map_err(error)
    }
}
