# yugetGIT

yugetGIT stores Minecraft world history in native Git repositories and tracks world changes at chunk level.

## Current Save Pipeline
1. Flush world data with `saveAllChunks(true, null)`.
2. Freeze saving with `disableLevelSaving = true`.
3. Explode changed `.mca` chunks into `staging/<dimension>/region/r.x.z/c.x.z.nbt`.
4. Commit `staging` + `meta` with native Git.
5. Run maintenance (`git gc --auto` and `git repack -a -d`).
6. Restore original save state.

Manual saves use a boss bar progress UI showing:
- Stage
- Percentage
- Changed chunk count
- Staged MB

## Commands
- `/backup save [-m "message"]`
- `/backup help`
- `/backup list [-all] [-start] [-(number)]`
- `/backup restore [-hash <id>] [-(number)]`
- `/backup delete [-all] [-hash <id>] [-(number)]`
- `/backup worlds`
- `/backup worlds delete "World Name"`
- `/backup status`
- `/backup debug-dialog`

Command parser behavior:
- Unknown flags are rejected.
- Missing `-hash` / `-m` values are rejected.
- Incompatible flags per subcommand are rejected.

## Restore Behavior
Restore now runs in-place without kicking players:
1. Freeze world saving.
2. Load `staging/meta` from target ref into repo working tree.
3. Reassemble region files back into world directories.
4. Re-enable world saving.
5. Force chunk/client refresh so restored state is visible without disconnecting.

## Status
- Core save pipeline: implemented.
- Boss bar save progress: implemented.
- Strict command validation: implemented.
- Push/Pull UX and timeline UI: in progress.