# Getting Started

## First Launch
1. Ensure Git is installed.
2. Ensure Git-LFS is installed.
3. Start Minecraft with yugetGIT.

## First Backup
1. Join a world.
2. Run `/backup save -m "first backup"`.
3. Run `/backup status` and confirm:
   - Git Resolved: Yes
   - Git-LFS Available: Yes
   - Repository Built: Yes
   - LFS Tracking Rules: Ready

## Quick Workflow
1. Save when you want snapshot points: `/backup save -m "message"`
2. View history: `/backup list -10`
3. Restore snapshot: `/backup restore -hash <id>` or `/backup restore -1`
