# yu Command

## Syntax
`/yu help`
`/yu init`
`/yu remote add <url>`
`/yu backup <help|save|list|details|restore|worlds|status>`
`/yu debug-dialog`
`/yu fetch [remote]`
`/yu push [--force]`
`/yu pull [--hard]`
`/yu merge`
`/yu reset --hard <ref>`

`/yu` is the dedicated git command entrypoint. In phase 1, only `init` is enabled.

## Phase 1 Command
- `/yu init`
  - Creates the local world repository if missing.
  - Ensures default branch is `main` and keeps `main` checked out after init.
  - Keeps `main` limited to a minimal generated `README.md`.
  - Creates world branch `world/<world-name>` for backup commits.
  - Removes legacy `.yugetgit/branch-index.md` if present.
  - Ensures operational repo config.
  - `/yu backup save` auto-switches to `world/<world-name>` before committing backup data.

## Phase 2 Command
- `/yu remote add <url>`
  - Sets or updates local `origin` remote URL.
  - Accepts full remote URL formats (for example `https://...` or `git@...`).
  - If a GitHub branch/blob page URL is pasted (for example `.../tree/...`), it is normalized to repo root automatically.
  - Does not create repositories on hosting providers.
  - Also pushes `main` to remote so the metadata branch is visible on the host.

## Phase 2.5 Commands
- `/yu backup list`
  - Shows the recent world backup commits in the same compact style as `/yu backup list`.
- `/yu fetch`
  - Runs `git fetch <remote> --prune` (default remote is `origin`).

## Phase 3 Commands
- `/yu push`
  - Pushes active world branch to `origin`.
  - Optional force mode: `/yu push --force`.
- `/yu pull`
  - Pulls current world branch from `origin` using rebase strategy.
  - Optional hard mode: `/yu pull --hard`.
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
2. (Optional) Run `/yu remote add <url>` when you are ready to attach a remote.
3. Run backup commands after init (for example `/yu backup save`).
4. Use `/yu push` or `/yu pull` for remote sync.

`/yu backup save` now requires init to be completed first.

## Important Note
Remote repository creation (GitHub, Forgejo, Gitea, GitLab, etc.) is user-managed.
yugetGIT only configures your local repository and remote URL.