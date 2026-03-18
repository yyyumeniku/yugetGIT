# Getting Started

## First Launch
1. Ensure Git is installed.
2. Ensure Git-LFS is installed.
3. Start Minecraft with yugetGIT.

## First Backup
1. Join a world.
2. Run `/yu init` to prepare the repository (`main` branch + minimal README metadata).
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

## Visual Diff Workflow
1. Run `/yu diff on` to generate a visual diff snapshot from tracked block changes.
2. Run `/yu diff refresh` after additional edits.
3. Use `/yu diff status` to check counts and `/yu diff clear` to reset tracked changes.
4. Open `Mods -> yugetGIT -> Config` and press `Open HUD Placement Editor` for HUD placement.
