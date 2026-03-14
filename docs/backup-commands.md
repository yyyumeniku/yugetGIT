# Backup Commands

## Syntax
`/yu backup <subcommand> [args...]`

Universal flags:
- `-all`
- `-(number)`
- `-start`
- `-hash <id>`
- `-m "message"`
- `-force` (for `/yu backup save` only)

## Commands
- `/yu backup help`
  - Shows command usage.
- `/yu backup save [-force] -m "message"`
  - Flushes world/chunks and entities, freezes save, stages data, commits backup.
  - Without `-force`, repeated manual saves can be skipped when movement guard is enabled and movement since last manual backup is minimal.
- `/yu backup save --force -m "message"`
  - Forces save even when movement guard would skip.

## Config Toggle
- `backup.manualSaveMovementGuardEnabled` (default: `true`)
  - `true`: `/yu backup save` may skip with a "No changes detected" message when movement is minimal.
  - `false`: `/yu backup save` always proceeds to content-based chunk staging and commit checks.
- `/yu backup list [-all] [-start] [-(number)]`
  - Lists backup history with chronological numbered entries (`#1` is your first commit in that world repo).
  - Use those numbers directly with restore (`/yu backup restore -4`).
- `/yu backup details -hash <id>`
  - Shows compact commit metadata for a specific commit hash.
- `/yu backup restore [-hash <id>] [-(number)]`
  - Restores selected backup, reapplies entity + block-entity state, starts reconnect countdown, then disconnects players.
  - `-N` targets the same commit number shown in `/yu backup list`.
- `/yu backup worlds`
  - Lists stored world repositories.
- `/yu backup worlds delete "World Folder"`
  - Deletes one world backup repository.
- `/yu backup status`
  - Shows runtime git/lfs/repo readiness.

## Debug Dialog
- `/yu debug-dialog`
  - Opens pre-launch debug dialog manually.
