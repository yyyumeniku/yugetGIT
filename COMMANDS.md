# yugetGIT — Command Documentation

This document explains every subcommand mapped to the core `/backup` infrastructure alongside exact execution rules and OS constraints.

## General Syntax
`/backup <subcommand> [args...]`
All subcommands accept unified modifier flags parsed by `ParsedArgs`:
*   `-all`: targets total repository states instead of individual instances.
*   `-(number)`: restricts the target list to a precise count (e.g. `-10` will show exactly 10).
*   `-start`: Reverses reading target loops from start rather than latest elements.
*   `-hash <id>`: Specifies an exact Git SHA for operations.
*   `-m "(msg)"`: Appends inline user arguments safely quoting strings.

### Commands
1. **`/backup help`**
   *   Shows command usage and argument examples.

1. **`/backup save [-m "msg"]`**
   *   Runs the safe sequence: `saveAllChunks` -> `disableLevelSaving=true` -> async chunk staging + git commit -> restore original save flag.
   *   Enforces repo-local Git-LFS setup (`git lfs install --local`) and tracking rules before commit.
   *   Stages changed chunks to `staging/<dimension>/region/r.x.z/c.x.z.nbt` and commits `staging` + `meta` + `.gitattributes`.
   *   Temporary progress is shown in a green segmented boss bar for players.
   *   Boss bar shows: stage, percent, changed chunks, and staged MB.
   *   `-m` requires a quoted multi-word message; unquoted single-token values are rejected.

2. **`/backup list [-start] [-(number)]`**
   *   Dumps recent history to chat. Example: `/backup list -5` fetches the latest 5 backups. 
   *   Prefixes total results cleanly before listing items.

3. **`/backup delete [-all] [-(number)]`**
   *   Rolls back exact commit counts. `/backup delete -3` permanently eradicates the last 3 saves natively from Git using `git reset --hard HEAD~3`.
   *   *Note*: To delete an entire world completely, use `/backup worlds delete "World"`.

4. **`/backup worlds`**
   *   Stand-alone: Lists stored repositories and space usage dynamically via Apache FileUtils traversal.
   *   Delete Mode: `/backup worlds delete "Name"` deletes raw directory footprints.

5. **`/backup restore [-hash id] [-(number)]`**
   *   Selectively targets Git refs and restores in-place.
   *   Loads `staging/meta` from the selected ref and reassembles `.mca` files directly into the active world.
   *   Invalid refs (for example `HEAD~1` when there is no previous commit) are rejected with a clear error.
   *   After a successful restore, the command starts a 5 second timer and disconnects the command sender with: `Restoration successful! You can enter your world again`.

6. **`/backup debug-dialog`**
   *   Forces a Java Swing window to appear simulating the UI MinGit setup sequence natively on client screens. Used for environment diagnostic flows across macOS/Windows setups.

## Save Pipeline Mapping
1. World flush and freeze runs in command/event layer.
2. `WorldSnapshotStager` scans world region folders and explodes only changed chunks via `ChunkExploder`.
   *   When a region has at least one changed chunk, `ChunkExploder` also fills any missing chunk baseline files for that region in staging so restore can reconstruct consistent region state for older commits.
3. `ChunkTimestamp` persists changed-chunk timestamps under `meta/timestamps.properties`.
4. `CommitBuilder` stages `staging`, `meta`, and `.gitattributes`, commits, then runs `git gc --auto` and `git repack -a -d`.
5. Completion callback restores the world save flag on the server thread.

## Command Validation
1. Unknown flags are rejected before command execution.
2. Missing values for `-hash` and `-m` are rejected.
3. Subcommands reject incompatible flags (for example, `save` only allows `-m`).
