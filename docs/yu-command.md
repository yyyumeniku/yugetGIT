# yu Command

## Syntax
`/yu help`
`/yu init`
`/yu repo add <url>`
`/yu backup <help|save|list|details|restore|worlds|status>`
`/yu debug-dialog`
`/yu fetch [remote]`
`/yu push [--force]`
`/yu pull`
`/yu merge`
`/yu reset --hard <ref>`

`/yu` is the dedicated git command entrypoint. In phase 1, only `init` is enabled.

## Phase 1 Command
- `/yu init`
  - Creates the local world repository if missing.
  - Ensures default branch is `main` and keeps `main` checked out after init.
  - Keeps `main` limited to `README.md` and `.yugetgit/` metadata files.
  - Creates world branch `world/<world-name>` for backup commits.
  - Creates/refreshes repository metadata files:
    - `README.md` (mod icon + mod version + branch index table)
    - `.yugetgit/mod-icon.png`
    - `.yugetgit/branch-index.md`
  - README branch index entries are clickable links to branch pages on git hosts that render markdown links.
  - Ensures operational repo config.
  - `/yu backup save` auto-switches to `world/<world-name>` before committing backup data.

## Phase 2 Command
- `/yu repo add <url>`
  - Sets or updates local `origin` remote URL.
  - Accepts full remote URL formats (for example `https://...` or `git@...`).
  - If a GitHub branch/blob page URL is pasted (for example `.../tree/...`), it is normalized to repo root automatically.
  - Does not create repositories on hosting providers.

## Phase 2.5 Commands
- `/yu backup list`
  - Shows the recent world backup commits in the same compact style as `/yu backup list`.
- `/yu fetch`
  - Runs `git fetch <remote> --prune` (default remote is `origin`).

## Phase 3 Commands
- `/yu push`
  - Pushes all local branches to `origin` in one operation.
  - Optional force mode: `/yu push --force`.
- `/yu pull`
  - Pulls current world branch from `origin` using rebase strategy.
- `/yu merge`
  - Pulls current world branch from `origin` using merge strategy.

Network note:
- `/yu` network commands can optionally disable TLS verification for self-signed remotes.

Config note (`run/config/yugetgit.cfg`):
- `gitNetwork.yuCommandTimeoutSeconds`: timeout for `/yu` network commands.
- `gitNetwork.allowInsecureTls`: when true, disables TLS certificate verification for `/yu` network commands (default is `false`).

## Reset Command
- `/yu reset --hard <ref>`
  - Runs hard reset against the given ref (example: `origin/main`).

Push and pull run directly and report the real git success/failure output.

## Required Flow
1. Run `/yu init` after entering world.
2. (Optional) Run `/yu repo add <url>` when you are ready to attach a remote.
3. Run backup commands after init (for example `/yu backup save`).
4. Use `/yu push` or `/yu pull` for remote sync.

`/yu backup save` now requires init to be completed first.

## Important Note
Remote repository creation (GitHub, Forgejo, Gitea, GitLab, etc.) is user-managed.
yugetGIT only configures your local repository and remote URL.