# Blockbase vs yugetGIT Chunk Change Detection Reverse Engineering

Date: 2026-03-15
Scope: Research-only analysis, no source edits.

## Sources Read

Mandatory files read in full from Blockbase:
- AI_BUILD_GUIDE.md
- CURSOR_SETUP.md
- MOD_BUILD_PLAN.md
- PROJECT.md

Blockbase repository mapping completed (excluding generated/vendor dirs), including:
- mod (Fabric Java)
- api (FastAPI + SQLite)
- web (Next.js)
- docs/plans at repo root

Primary Blockbase detection implementation inspected:
- mod/src/main/java/com/blockbase/BlockTracker.java
- mod/src/main/java/com/blockbase/mixin/BlockItemMixin.java
- mod/src/main/java/com/blockbase/Blockbase.java
- mod/src/main/java/com/blockbase/mixin/LevelMixin.java
- mod/src/main/java/com/blockbase/BlockbaseCommands.java
- mod/src/main/java/com/blockbase/StagingArea.java
- mod/src/main/java/com/blockbase/DiffCalculator.java
- mod/src/main/java/com/blockbase/DiffViewManager.java
- mod/src/main/java/com/blockbase/DiffOverlayRenderer.java

Primary yugetGIT implementation inspected:
- [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java)
- [src/main/java/com/yugetGIT/core/mca/WorldSnapshotStager.java](src/main/java/com/yugetGIT/core/mca/WorldSnapshotStager.java)
- [src/main/java/com/yugetGIT/core/mca/ChunkTimestamp.java](src/main/java/com/yugetGIT/core/mca/ChunkTimestamp.java)
- [src/main/java/com/yugetGIT/events/WorldSaveHandler.java](src/main/java/com/yugetGIT/events/WorldSaveHandler.java)
- [src/main/java/com/yugetGIT/core/git/CommitBuilder.java](src/main/java/com/yugetGIT/core/git/CommitBuilder.java)
- [src/main/java/com/yugetGIT/config/yugetGITConfig.java](src/main/java/com/yugetGIT/config/yugetGITConfig.java)
- [src/main/java/com/yugetGIT/ui/SaveProgressOverlay.java](src/main/java/com/yugetGIT/ui/SaveProgressOverlay.java)

---

## A) Reverse-Engineering: How Blockbase Detects Changes and Avoids Huge False Positives

## 1) Detection model

Blockbase does not infer dirtiness from region file timestamps or full-world save scans.
It tracks explicit gameplay mutations at event/mixin boundaries:

- Breaks: PlayerBlockBreakEvents.BEFORE in Blockbase.java (line 53) calls trackBlockBreak.
- Places: BlockItemMixin injects at BlockItem.place return and calls trackBlockPlace (BlockItemMixin.java line 21 and line 38).
- State modifications: LevelMixin injects Level.setBlock and calls trackBlockModify only for non-place/non-break transitions (LevelMixin.java lines 43-46 and line 61).

This means Blockbase records a delta stream of changed positions, not a save-time snapshot diff.

## 2) Noise suppression in detector path

LevelMixin filters high-frequency interaction noise:
- Door, trapdoor, button, pressure plate, fence gate state changes are skipped (LevelMixin.java lines 51-55).

This prevents a large class of non-build-intent updates from entering tracked changes.

## 3) Commit lifecycle keeps working set clean

- Stage uses current tracked change list once (BlockbaseCommands.java line 300 stageAll).
- Commit clears staging and clears tracked changes (BlockbaseCommands.java line 383, BlockTracker.clearChanges).

After commit, there is no residual stale detector state, so repeated idle commits do not re-emit historical deltas.

## 4) Visual diff is constrained and non-destructive

- DiffCalculator computes over bounded positions and player-centered radius, not whole-world dump.
- It unions previous known positions + currently tracked changes, then inspects those positions only.
- Overlay rendering is visual-only and does not mutate world data (DiffOverlayRenderer).

Result: even when diffing, scope remains bounded; detector and visualizer are decoupled.

## Why Blockbase avoids 200k-500k false positives

Because it never uses world-save side effects (region rewrite timestamps) as the signal of logical changes. Detection input is player/world mutation events only.

---

## B) Root-Cause Analysis: yugetGIT False Positives (Idle Save Massive Deltas)

## Primary root cause

yugetGIT uses save-time MCA timestamp advancement as dirty trigger, which is not equivalent to gameplay content changes.

Evidence:
- Timestamp gate in explodeRegionFile compares MCA header per-chunk timestamp against stored value: [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L73), [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L85).
- Commit pipeline explicitly calls world.saveAllChunks(true, null) before staging: [src/main/java/com/yugetGIT/events/WorldSaveHandler.java](src/main/java/com/yugetGIT/events/WorldSaveHandler.java#L232).

Interpretation:
- saveAllChunks(true, null) can advance chunk last-updated metadata broadly.
- Exploder interprets this as potential content change for many chunks.
- This creates very large candidate sets on otherwise idle saves.

## Amplifier 1: Region-wide baseline fill behavior

When any chunk in region appears changed, missing baseline chunk files in that region are written as part of “reconstructible baseline” behavior:
- [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L124), [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L125), [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L126).

Effect:
- One timestamp-triggered chunk can cause many writes if baseline coverage is incomplete.

## Amplifier 2: Section comparison is order/size sensitive

Chunk equivalence compares section lists by index and equal list size:
- [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L207), [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L209), [src/main/java/com/yugetGIT/core/mca/ChunkExploder.java](src/main/java/com/yugetGIT/core/mca/ChunkExploder.java#L213).

If serialization normalizes section ordering or empties differently across saves, this can mark unchanged chunks as different.

## Amplifier 3: “changedChunks” metric mixes non-chunk data

Auxiliary file changes are added into changedChunks counter:
- [src/main/java/com/yugetGIT/core/mca/WorldSnapshotStager.java](src/main/java/com/yugetGIT/core/mca/WorldSnapshotStager.java#L104).

Effect:
- Reported “chunk changes” can be inflated by non-chunk auxiliary updates.

## Contributing system behavior

- Auto commit runs from save hooks and interval triggers: [src/main/java/com/yugetGIT/events/WorldSaveHandler.java](src/main/java/com/yugetGIT/events/WorldSaveHandler.java#L109), [src/main/java/com/yugetGIT/events/WorldSaveHandler.java](src/main/java/com/yugetGIT/events/WorldSaveHandler.java#L103).
- CommitBuilder stages with git add -A over staging/meta each run: [src/main/java/com/yugetGIT/core/git/CommitBuilder.java](src/main/java/com/yugetGIT/core/git/CommitBuilder.java#L144).

So detector overreach immediately becomes repository-scale noise.

---

## C) Step-by-Step Migration/Fix Plan Strictly Following Blockbase Behavior

Guiding rule from Blockbase reverse-engineering:
- Dirty-state source must be mutation events, not save-file timestamps.
- Visual diff must consume validated changed-position sets only.

## Phase 1: Chunk dirty-state detection rewrite

Goal: Replace timestamp-driven snapshot inference with mutation-event dirty index.

1. Introduce chunk dirty index manager.
- Maintain a set/map of dirty chunk coordinates keyed by dimension + chunk x/z.
- Mark dirty on block place/break/state mutation events only (Blockbase pattern).
- Optional filters for noisy state toggles (Blockbase LevelMixin filter pattern).

2. Wire Forge events/mixins for 1.12.2 equivalents.
- Block place event marks affected chunk dirty.
- Block break event marks affected chunk dirty.
- Block state mutation path marks dirty for non-place/non-break transitions.
- Keep suppression guards for internal restore/save routines.

3. Stage only dirty chunks.
- In stageWorld, iterate dirty chunk index and map to region files.
- Read/write only those chunks, no global 1024-slot timestamp scan.
- Remove MCA header timestamp gate from eligibility logic.

4. Preserve chunk equivalence as secondary guard, not primary detector.
- Keep semantic equivalence check before write.
- Update section compare to section-Y keyed comparison, not index-position comparison.
- Keep volatile key stripping for non-build-intent fields.

5. Dirty index lifecycle.
- On successful commit: clear dirty index (Blockbase clearChanges behavior).
- On failed commit: retain dirty index for retry.
- Persist dirty index between sessions only if necessary; otherwise reconstruct from events during runtime.

6. Metrics correctness.
- Separate counters:
  - changedChunkFiles
  - changedAuxFiles
  - changedTotalFiles
- Do not add auxiliary counts into changedChunks.

Exit condition for Phase 1:
- Idle save operations produce zero staged chunk changes across repeated cycles.

## Phase 2: Visual diff rebuild after chunk fix validation only

Goal: Build/restore diff visuals only on top of trusted change sets.

1. Freeze current diff expansion work until Phase 1 tests pass.
2. Recompute diff datasets from commit snapshots + validated changed position sets.
3. Keep render mode non-destructive and scoped (Blockbase DiffViewManager and DiffOverlayRenderer model).
4. Enforce bounded radius or selected-area scope for large worlds.

Exit condition for Phase 2:
- Diff overlays reflect real changed chunks/blocks with no idle-save ghost changes.

## Phase 3: Forge ModList settings flow (no standalone command)

Goal: Move user settings UX to standard Forge mod settings path.

1. Use Forge config-backed settings as single source of truth.
- yugetGIT already has @Config and sync hook in [src/main/java/com/yugetGIT/config/yugetGITConfig.java](src/main/java/com/yugetGIT/config/yugetGITConfig.java#L9).

2. Remove/avoid command-only configuration pathways for runtime options.
- Keep commands for operations (save/list/restore/push/pull), not preference editing.

3. Ensure all toggles needed for backup detector/diff/HUD are represented in config categories and visible from Mods list config UI.

Exit condition for Phase 3:
- User can configure behavior from ModList config flow without standalone “settings command”.

## Phase 4: HUD placement UI rebuilt inside settings

Goal: HUD position/editability controlled in settings UI, not ad hoc overlays.

1. Add HUD settings model (anchor, offset, scale, visibility).
2. Integrate live preview in settings screen.
3. Persist via config and apply on load.
4. Keep overlay renderer read-only against tracker state.

Exit condition for Phase 4:
- HUD position is configurable entirely from settings UX and persists reliably.

---

## D) Acceptance Criteria and Validation Tests (Must Pass Before UI Work)

## Acceptance criteria

1. Idle save stability
- After baseline commit, 20 consecutive idle world saves produce:
  - changedChunkFiles = 0 every cycle
  - no new commit unless non-chunk tracked data intentionally changed

2. Deterministic no-op commit behavior
- Running backup save twice without world mutations yields second run as no-op.

3. Mutation sensitivity
- Single block place modifies exactly one expected chunk output.
- Single block break modifies exactly one expected chunk output.
- Controlled redstone/property change updates only targeted chunks (subject to filter policy).

4. Counter correctness
- Auxiliary file changes do not increment changedChunkFiles.

5. Commit lifecycle
- Successful commit clears dirty chunk index.
- Failed commit preserves dirty index for retry.

6. Diff gating
- Visual diff rebuild is blocked until detector tests pass.

## Required validation tests

1. Unit tests
- DirtyChunkIndex mark/clear semantics.
- Section equivalence by section Y key matching (order-insensitive).
- Volatile key stripping behavior.

2. Integration tests
- Save hook + commit pipeline with no world mutation -> no staged chunks.
- Save hook + single mutation -> bounded expected staged chunks.
- Repeated saveAllChunks(true, null) with no mutations -> still no staged chunks.

3. Regression tests
- Existing restore/assembly flows still reconstruct correctly.
- Entity/block-entity snapshot capture remains functional and isolated from chunk dirty counters.

UI work (Phases 3-4) must not start until all detector acceptance tests are green.

---

## E) Component Map: Relevant Blockbase vs yugetGIT Side-by-Side

Blockbase relevant components:
- Detector: BlockTracker + BlockItemMixin + LevelMixin + Block break event hook
- Commit path: BlockbaseCommands stage/commit, StagingArea
- Diff: DiffCalculator + DiffViewManager + DiffOverlayRenderer
- Persistence: .blockbase changes/commits JSON

yugetGIT relevant components:
- Detector (current): ChunkExploder timestamp gate + chunk equivalence
- Stager: WorldSnapshotStager + ChunkTimestamp
- Commit path: CommitBuilder + WorldSaveHandler save hooks
- UI: SaveProgressOverlay
- Config: yugetGITConfig

Core architectural mismatch:
- Blockbase is event-driven delta tracking.
- yugetGIT is save-time snapshot inference driven by MCA timestamps.

That mismatch is the direct reason Blockbase avoids large false positives while yugetGIT can emit huge idle-save dirty sets.
