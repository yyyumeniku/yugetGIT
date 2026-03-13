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
  - Lists backup history.
- `/backup restore [-hash <id>] [-(number)]`
  - Restores selected backup, reapplies entity positions, starts reconnect countdown, then disconnects players.
- `/backup delete [-all] [-hash <id>] [-(number)]`
  - Deletes rollback targets from history.
- `/backup worlds`
  - Lists stored world repositories.
- `/backup worlds delete "World Folder"`
  - Deletes one world backup repository.
- `/backup status`
  - Shows runtime git/lfs/repo readiness.
- `/backup debug-dialog`
  - Opens pre-launch debug dialog manually.
