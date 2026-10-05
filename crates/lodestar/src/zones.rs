//! Zones: groups of regions close enough together that one node has to
//! simulate all of them.
//!
//! An update can only travel between chunks that are loaded, so two groups of
//! loaded chunks with enough unloaded ground between them cannot affect each
//! other within a tick. Each such group can then be simulated by a different
//! node without anything crossing from one owner to another.

use std::collections::BTreeSet;

use lode_protocol::RegionKey;

/// Active regions at most this many regions apart, on both axes, are in the
/// same zone. Two zones are therefore separated by at least `REACH` whole
/// regions that nobody has loaded, and players have to cross all of that
/// before their chunks can touch, which leaves time to hand one zone over to
/// the other's owner first.
pub const REACH: i32 = 2;

/// Splits active regions into zones. The result is deterministic: each zone is
/// sorted, and zones are ordered by their first region.
pub fn zones(regions: impl IntoIterator<Item = RegionKey>) -> Vec<Vec<RegionKey>> {
    let mut left: BTreeSet<RegionKey> = regions.into_iter().collect();
    let mut out = Vec::new();
    while let Some(start) = left.pop_first() {
        let mut zone = vec![start];
        let mut next = 0;
        while next < zone.len() {
            let region = zone[next];
            next += 1;
            for dx in -REACH..=REACH {
                for dz in -REACH..=REACH {
                    let near = RegionKey {
                        dim: region.dim,
                        x: region.x.saturating_add(dx),
                        z: region.z.saturating_add(dz),
                    };
                    if left.remove(&near) {
                        zone.push(near);
                    }
                }
            }
        }
        zone.sort();
        out.push(zone);
    }
    out
}
