# Entities Backup + Restore

## Goal
Entity positions should return to where they were at backup save time.

## Current Behavior
- On backup save, yugetGIT captures loaded non-player entity positions into `meta/entities.dat`.
- On restore, yugetGIT restores chunk data and reapplies those saved positions to matching loaded entities by UUID.
- Players are not included in this entity snapshot.

## Restore Sequence
1. Load backup `staging` and `meta`.
2. Reassemble region files.
3. Reposition loaded entities from `meta/entities.dat`.
4. Start reconnect countdown and disconnect players.

## Scope Notes
- Repositioning is applied to entities currently loaded at restore time.
- Entities not loaded at that moment will still rely on restored chunk NBT state.
