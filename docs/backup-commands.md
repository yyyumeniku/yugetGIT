# Backup Commands

## Syntax
`/backup <subcommand> [args...]`

Universal flags:
- `-all`
- `-(number)`
- `-start`
- `-hash <id>`
- `-m "message"`
- `-force` (for `/backup save` only)

## Commands
- `/backup help`
  - Shows command usage.
- `/backup save [-force] -m "message"`
  - Flushes world/chunks and entities, freezes save, stages data, commits backup.
  - Without `-force`, repeated manual saves can be skipped when movement guard is enabled and movement since last manual backup is minimal.
- `/backup fsave -m "message"`
  - Shortcut for a forced manual save (`/backup save -force ...`).

## Config Toggle
- `backup.manualSaveMovementGuardEnabled` (default: `true`)
  - `true`: `/backup save` may skip with a "No changes detected" message when movement is minimal.
  - `false`: `/backup save` always proceeds to content-based chunk staging and commit checks.
- `/backup list [-all] [-start] [-(number)]`
  - Lists backup history with chronological numbered entries (`#1` is your first commit in that world repo).
  - Use those numbers directly with restore (`/backup restore -4`).
- `/backup restore [-hash <id>] [-(number)]`
  - Restores selected backup, reapplies entity + block-entity state, starts reconnect countdown, then disconnects players.
  - `-N` targets the same commit number shown in `/backup list`.
- `/backup delete [-all] [-hash <id>] [-(number)]`
  - Deletes rollback targets from history.
  - You can now delete multiple commit numbers in one command, for example: `/backup delete -2 3 5 7`.
  - Multi-delete numbers follow `/backup list` numbering.
- `/backup worlds`
  - Lists stored world repositories.
- `/backup worlds delete "World Folder"`
  - Deletes one world backup repository.
- `/backup status`
  - Shows runtime git/lfs/repo readiness.
- `/backup debug-dialog`
  - Opens pre-launch debug dialog manually.
