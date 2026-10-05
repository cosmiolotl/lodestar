use crate::world::{EPOCH, JOURNAL, READ, REVISION, Request, WorldStore, response};
use bytes::Bytes;

pub fn execute(
    store: Option<&WorldStore>,
    request: Request,
    committed: bool,
) -> std::io::Result<Bytes> {
    let Some(store) = store else {
        return Ok(response(request.id, 4, b"world storage is disabled"));
    };
    let result = match request.operation {
        READ => store.read(&request, committed),
        EPOCH => store
            .epoch()
            .map(|epoch| Some(Bytes::copy_from_slice(&epoch.to_be_bytes()))),
        REVISION => store
            .revision()
            .map(|revision| Some(Bytes::copy_from_slice(&revision.to_be_bytes()))),
        JOURNAL => {
            let offset = ((request.x as u32 as u64) << 32) | request.z as u32 as u64;
            store
                .journal(&request.dimension, offset, committed)
                .map(Some)
        }
        _ => store.write(&request).map(|()| Some(Bytes::new())),
    };
    Ok(match result? {
        Some(data) => response(request.id, 0, &data),
        None => response(request.id, 1, &[]),
    })
}
