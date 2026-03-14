# yu Command

## Syntax
`/yu help`
`/yu init`
`/yu repo add <url>`
`/yu list`
`/yu fetch [remote]`
`/yu push [--force]`
`/yu pull`
`/yu merge`
`/yu reset --hard <ref>`

`/yu` is the dedicated git command entrypoint. In phase 1, only `init` is enabled.

## Phase 1 Command
- `/yu init`
  - Creates the local world repository if missing.
  - Ensures operational repo config.
  - Switches/creates branch: `world/<world-name>`.

## Phase 2 Command
- `/yu repo add <url>`
  - Sets or updates local `origin` remote URL.
  - Accepts full remote URL formats (for example `https://...` or `git@...`).
  - Does not create repositories on hosting providers.

## Phase 2.5 Commands
- `/yu list`
  - Shows the recent world backup commits in the same compact style as `/backup list`.
- `/yu fetch`
  - Runs `git fetch <remote> --prune` (default remote is `origin`).

## Phase 3 Commands
- `/yu push`
  - Pushes current world branch to `origin`.
  - Optional force mode: `/yu push --force`.
- `/yu pull`
  - Pulls current world branch from `origin` using rebase strategy.
- `/yu merge`
  - Pulls current world branch from `origin` using merge strategy.

## Reset Command
- `/yu reset --hard <ref>`
  - Runs hard reset against the given ref (example: `origin/main`).

Push and pull run directly and report the real git success/failure output.

## Required Flow
1. Run `/yu init` after entering world.
2. (Optional) Run `/yu repo add <url>` when you are ready to attach a remote.
3. Run backup commands after init (for example `/backup save`).
4. Use `/yu push` or `/yu pull` for remote sync.

`/backup save` now requires init to be completed first.

## Important Note
Remote repository creation (GitHub, Forgejo, Gitea, GitLab, etc.) is user-managed.
yugetGIT only configures your local repository and remote URL.