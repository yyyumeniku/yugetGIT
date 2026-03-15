# Chunk Dirty Tracking (Event-Driven)

## Goal
Prevent idle save false positives by staging and exploding only chunks explicitly marked dirty by gameplay-relevant block mutation events.

## What changed
- Dirty state source now comes from Forge block mutation events, not MCA timestamp drift.
- Dirty chunks are indexed by world + dimension + chunk coordinates.
- Staging resolves only dirty chunks into region targets.
- Region explosion writes only dirty chunk files when semantic chunk content actually differs.
- Auxiliary world files are counted separately from chunk file changes.
- Dirty chunk index is cleared only after successful/no-op commit outcomes.

## Event sources
Implemented in `ChunkDirtyTrackerHandler`:
- `BlockEvent.BreakEvent`
- `BlockEvent.PlaceEvent`
- `BlockEvent.MultiPlaceEvent`
- `BlockEvent.FluidPlaceBlockEvent`
- `BlockEvent.CropGrowEvent.Post` (configurable)

## Noise filter
Interactive block toggles can be ignored by config (enabled by default):
- doors
- trapdoors
- buttons
- pressure plates
- fence gates

## Config location
All options are in Forge ModList config (`yugetGITConfig.backup`):
- `trackCropGrowthChanges`
- `ignoreInteractiveStateToggles`

No standalone command configuration was added.

## Pipeline integration
1. Gameplay event marks chunk dirty.
2. Save/commit flow snapshots dirty set for active world.
3. Stager maps dirty chunks to region files and local chunk slots.
4. Exploder writes only changed dirty chunk NBT files.
5. Commit proceeds on actual file deltas.
6. Dirty set cleared only when commit resolves successfully or no-op.
