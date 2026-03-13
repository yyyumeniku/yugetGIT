# Backup Commands

## Syntax
`/backup <subcommand> [args...]`

Universal flags:
- `-all`
- `-(number)`
- `-start`
- `-hash <id>`
- `-m "message"`

## Commands
- `/backup help`
  - Shows command usage.
- `/backup save -m "message"`
  - Flushes world/chunks and entities, freezes save, stages data, commits backup.
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
