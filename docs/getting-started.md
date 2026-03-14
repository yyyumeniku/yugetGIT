# Getting Started

## First Launch
1. Ensure Git is installed.
2. Ensure Git-LFS is installed.
3. Start Minecraft with yugetGIT.

## First Backup
1. Join a world.
2. Run `/yu init` to prepare the repository (`main` branch + metadata README/index/icon).
3. Run `/yu backup save -m "first backup"`.
4. Run `/yu backup status` and confirm:
   - Git Resolved: Yes
   - Git-LFS Available: Yes
   - Repository Built: Yes
   - LFS Tracking Rules: Ready

## Quick Workflow
1. Save when you want snapshot points: `/yu backup save -m "message"`
2. View history: `/yu backup list -10`
3. Restore snapshot: `/yu backup restore -hash <id>` or `/yu backup restore -1`
