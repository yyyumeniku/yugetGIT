# Entities Backup + Restore

## Goal
Entity positions should return to where they were at backup save time.

## Current Behavior
- On backup save, yugetGIT captures loaded non-player entity positions into `meta/entities.dat`.
- On backup save, yugetGIT captures loaded block-entity NBT into `meta/block_entities.dat`.
- On restore, yugetGIT restores chunk data and reapplies those saved positions to matching loaded entities by UUID.
- On restore, yugetGIT also reapplies block-entity NBT to matching loaded block entities (for example chests, furnaces, dispensers, and similar tile entities).
- Players are not included in this entity snapshot.

## Restore Sequence
1. Load backup `staging` and `meta`.
2. Reassemble region files.
3. Reposition loaded entities from `meta/entities.dat`.
4. Refresh loaded block entities from `meta/block_entities.dat`.
5. Start reconnect countdown and disconnect players.

## Scope Notes
- Repositioning is applied to entities currently loaded at restore time.
- Entities not loaded at that moment will still rely on restored chunk NBT state.
