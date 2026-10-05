//! Atomic, one-time import of the previous coordinator's files. Worker caches are never imported.
use crate::world::{Request, WRITE, WorldStore};
use bytes::Bytes;
use std::{io, path::Path};

pub fn import(store: &WorldStore, root: &Path) -> io::Result<()> {
    if store.imported()? {
        return Ok(());
    }
    let players = crate::storage::PlayerStore::open(root)?;
    for (uuid, data) in players.load_all()? {
        store.save_player(uuid, &data)?;
    }
    let directory = root.join("world");
    if directory.exists() {
        for dimension in std::fs::read_dir(directory)? {
            let dimension = dimension?;
            if !dimension.file_type()?.is_dir() {
                continue;
            }
            let name = decode(&dimension.file_name().to_string_lossy())?;
            for kind in std::fs::read_dir(dimension.path())? {
                let kind = kind?;
                if !kind.file_type()?.is_dir() {
                    continue;
                }
                let kind_name = decode(&kind.file_name().to_string_lossy())?;
                for file in std::fs::read_dir(kind.path())? {
                    let file = file?;
                    let path = file.path();
                    if kind_name == "journal" && file.file_name() == "state.log" {
                        import_journal(store, &name, &std::fs::read(path)?)?;
                    } else if path.extension().is_some_and(|extension| extension == "nbt") {
                        let stem = path.file_stem().unwrap().to_string_lossy();
                        let (x, z) = stem.split_once('.').ok_or_else(invalid)?;
                        store.write(&Request {
                            id: 0,
                            operation: WRITE,
                            dimension: name.clone(),
                            kind: kind_name.clone(),
                            x: x.parse().map_err(|_| invalid())?,
                            z: z.parse().map_err(|_| invalid())?,
                            data: Bytes::from(std::fs::read(path)?),
                        })?;
                    }
                }
            }
        }
    }
    store.finish_import()
}

fn decode(value: &str) -> io::Result<String> {
    let bytes = value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let text = std::str::from_utf8(pair).map_err(|_| invalid())?;
            u8::from_str_radix(text, 16).map_err(|_| invalid())
        })
        .collect::<io::Result<Vec<_>>>()?;
    if value.len() % 2 != 0 {
        return Err(invalid());
    }
    String::from_utf8(bytes).map_err(|_| invalid())
}

fn import_journal(store: &WorldStore, scope: &str, mut data: &[u8]) -> io::Result<()> {
    while !data.is_empty() {
        if data.len() < 4 {
            return Err(invalid());
        }
        let size = u32::from_be_bytes(data[..4].try_into().unwrap()) as usize;
        data = &data[4..];
        if size > lode_protocol::MAX_FRAME - 32 || size > data.len() {
            return Err(invalid());
        }
        store.append(scope, &data[..size])?;
        data = &data[size..];
    }
    Ok(())
}

fn invalid() -> io::Error {
    io::Error::other("invalid legacy world record; import rolled back")
}
