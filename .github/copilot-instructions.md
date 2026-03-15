# yugetGIT — Copilot Instructions (1.12.2, All Platforms)

## What This Mod Does
yugetGIT version-controls Minecraft 1.12.2 world saves using native git. Before each
commit, .mca region files are exploded into per-chunk .nbt files so git only tracks
changed chunks. On first launch a Swing dialog (shown before Minecraft renders) checks
for git and asks the user if they want to download it. GitHub push/pull uses the user's
existing git credentials — no OAuth, no token management inside the mod. The in-game
yugetGIT UI lets users rename repos and choose between single-repo-with-branches or
one-repo-per-world mode.

## Build Toolchain
- Loader: Cleanroom Loader (Forge 14.23.5.2847 — Java 25, LWJGL3, Mixin support)
- Build: RetroFuturaGradle 2.0.2 + Gradle 9.2.1 (CleanroomMC/ForgeDevEnv template)
- Java: 25 toolchain, Java 8 bytecode target (sourceCompatibility = JavaVersion.VERSION_1_8)
- No shading needed — git downloaded at runtime, not bundled

## Git Strategy
DO NOT use JGit. Use native git via ProcessBuilder.
Reason: FastBack originally used JGit and dropped it — binary file performance and
compatibility problems at scale. Native git runs in a separate OS process, never
blocking the Minecraft thread.
*(For more info and architecture context on how FastBack does things, check out: https://pcal43.github.io/fastback/ and the library it uses https://github.com/lemire/javaewah)*

## Research Escalation Rule
If errors persist after local debugging attempts OR the user explicitly asks for external research,
the agent must consult internet sources before the next code-change iteration.

Required research scope:
- Official docs (Git, Forge, Java, library docs)
- GitHub issues/discussions and source references
- Community troubleshooting (forums, Reddit, Stack Overflow)

After research:
1. Summarize the relevant findings briefly in chat.
2. Apply a concrete fix in code.
3. Re-run validation (build/tests/runtime checks) and report the result.

---

## Pre-Launch Swing Dialog (First Run)

### When it appears
This dialog runs in a Mixin or coremod hook that fires before FML begins loading mods
— before any Minecraft rendering is initialized. Java Swing is available at this stage
because the JVM is running but LWJGL is not yet active. This is the same pattern used
by CleanroomRelauncher.

The dialog only appears on first run OR if git is no longer found at the stored path.
Store a `yugetGIT.firstRun` flag in `.minecraft/yugetGIT/state.properties`.

### Implementation (PreLaunchGitDialog.java)
```java
// called from early coremod/mixin, before FML mod loading
public static void showIfNeeded() {
    if (!StateProperties.isFirstRun() && GitBootstrap.isGitResolved()) return;
    SwingUtilities.invokeAndWait(() -> showDialog());
}

private static void showDialog() {
    // Use JOptionPane for simplicity — no custom JFrame needed
    String[] options = {"Download Git", "Use System Git", "Skip (disable backups)"};
    int choice = JOptionPane.showOptionDialog(
        null,
        "yugetGIT needs Git to version-control your worlds.\n\n" +
        "Git was not found on this system.\n" +
        "Would you like yugetGIT to download a portable copy?",
        "yugetGIT — Git Required",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.INFORMATION_MESSAGE,
        null,
        options,
        options[0]
    );
    // handle choice:
    // 0 → GitBootstrap.downloadPortableGit() with progress dialog
    // 1 → GitBootstrap.resolveFromPath() — show error if still not found
    // 2 → yugetGITConfig.backupsEnabled = false, skip all git ops
}
```

### Download progress dialog (DownloadProgressDialog.java)
If user chose "Download Git", show a second non-blocking JDialog with:
- A JProgressBar (indeterminate until size is known, then determinate)
- A JLabel: "Downloading MinGit... 14.2 MB / 31.0 MB (1.2 MB/s)"
- A Cancel button

Update from a background SwingWorker that streams the download.
On completion: close dialog, call GitBootstrap.extractAndValidate().
On cancel: delete partial download, set backupsEnabled = false.

### What counts as "git found"
After dialog, validate by running `git --version` with the resolved binary.
Require minimum git 2.37.0. If older: warn in dialog, continue with fsmonitor disabled.

### State file (.minecraft/yugetGIT/state.properties)
```
firstRun=false
gitPath=/absolute/path/to/git
gitVersion=2.43.0
backupsEnabled=true
```

---

## GitHub Integration: Use Existing Git Credentials

### Design principle
Do not manage tokens, OAuth, or credentials inside yugetGIT. The user is expected to
have git configured on their machine. yugetGIT detects whether their git setup is
sufficient and tells them clearly if it is not.

This is cleaner, more secure, and requires zero auth code in the mod.

### Credential check (GitCredentialChecker.java)
Run these checks via ProcessBuilder against the resolved git binary:

```
check 1: git config --global user.name
  → empty output = not configured

check 2: git config --global user.email
  → empty output = not configured

check 3: git config --global credential.helper
  → empty = no credential helper (push/pull will hang on auth prompt)
  → "manager", "manager-core", "osxkeychain", "store", "wincred" = configured

check 4: git ls-remote <remoteUrl> HEAD (only when user attempts a push/pull)
  → exit 0 = auth works
  → exit 128 = auth failed
```

### If credentials are not configured
Show a clear in-game chat message and block push/pull:
```
[yugetGIT] Push/pull requires git to be configured with GitHub credentials.
[yugetGIT] Run these commands in a terminal:
[yugetGIT]   git config --global user.name "Your Name"
[yugetGIT]   git config --global user.email "you@example.com"
[yugetGIT] Then authenticate once by running: git push <your-repo-url>
[yugetGIT] After that, yugetGIT push/pull will work automatically.
```

Show the same message in the yugetGIT UI settings panel as a yellow warning strip
with a "Check Again" button that re-runs GitCredentialChecker.

### Credential helper per platform (for the warning message)
Tell the user which helper their platform uses if none is configured:
- Windows: "Git Credential Manager is included with Git for Windows"
- macOS: "Run: git config --global credential.helper osxkeychain"
- Linux: "Run: git config --global credential.helper store"

---

## Repo Mode: Single-Repo vs Per-World

### Config option
```
repoMode = PER_WORLD        # default: one repo per world
                            # or: SINGLE_REPO (all worlds in branches)
```

### PER_WORLD mode (default)
Each world gets its own git repository:
```
.minecraft/yugetGIT/worlds/
    MyWorld/
        .git/
        region/...
        staging/...
    AnotherWorld/
        .git/
        ...
```

Remote mapping: each world pushes to its own GitHub repo.
Repo name defaults to world name. User can rename in yugetGIT UI.
Remote URL pattern: `https://github.com/<user>/<repoName>`

### SINGLE_REPO mode
All worlds share one git repository, each world on its own branch:
```
.minecraft/yugetGIT/
    repo/
        .git/
        (branch: world/MyWorld)     ← checked out when MyWorld is active
        (branch: world/AnotherWorld)
```

On world load: `git checkout world/<worldName>` (create if not exists)
On world exit: commit + checkout back to a neutral detached HEAD
Remote: one GitHub repo, all world branches pushed to it.
Repo name configurable in yugetGIT UI (default: "minecraft-worlds").

### Switching between modes
Switching modes is a destructive migration — warn the user and require confirmation.
Provide a /yu backup migrate command that handles the transition.

---

## yugetGIT UI: Settings Panel

The settings panel is accessible from the main yugetGIT timeline GUI via a gear icon
button (vanilla GuiButton styled). It allows the user to configure repo settings
without editing files.

### Settings panel contents (GuiScreen, vanilla styled)

**Repo Mode**
- Two GuiButton options: "One repo per world" / "All worlds in one repo"
- Changing mode shows a confirmation dialog before migrating

**Repo Name (per world)**
- Text field (GuiTextField) showing current repo name for the active world
- Default: world's folder name (sanitized: spaces → hyphens, lowercase)
- On change: renames the remote URL, optionally renames the GitHub repo via
  `git remote set-url origin https://github.com/<user>/<newName>`
- Validation: disallow characters invalid in GitHub repo names

**Remote URL**
- Text field showing current remote URL
- Editable — user can point to GitLab, Gitea, etc. (not GitHub-only)

**Credential Status**
- Read-only label: "Git credentials: Configured" (green) or "Not configured" (yellow)
- "Check Again" button: re-runs GitCredentialChecker

**Push/Pull Settings**
- Toggle: Pull on launch (default: on)
- Toggle: Push on exit (default: off)
- Number field: Push every N commits (0 = disabled)

**Backup Settings**
- Toggle: Auto-commit on world save (default: on)
- Toggle: Commit on player death (default: off)
- Number field: Commit interval in minutes (0 = every save)
- Text area: Ignore list (one path pattern per line)

---

## Cross-Platform Git Bootstrap

### Platform detection matrix
Detect via System.getProperty("os.name") and System.getProperty("os.arch").
Normalize: "aarch64" and "arm64" → same ARM64 enum value.

| OS      | Arch   | Strategy                                              |
|---------|--------|-------------------------------------------------------|
| Windows | x64    | Download MinGit x64 zip → .minecraft/yugetGIT/git/   |
| Windows | ARM64  | Download MinGit ARM64 zip → .minecraft/yugetGIT/git/ |
| macOS   | any    | Check PATH → fallback: Swing dialog with brew hint   |
| Linux   | any    | Check PATH → fallback: Swing dialog with pkg hint    |

### ProcessBuilder environment (always set)
- GIT_CONFIG_NOSYSTEM=1
- GIT_TERMINAL_PROMPT=0
- HOME=<user home dir>
- On Windows: GIT_EXEC_PATH=<mingit>/libexec/git-core/

### Per-platform fsmonitor
- Windows (git 2.37+): core.fsmonitor = true
- macOS (git 2.37+): core.fsmonitor = true
- Linux: core.fsmonitor = false (no inotify in built-in daemon)
  Optional: detect Watchman, configure hook if present

---

## Git Repository Configuration (on repo init)
```ini
[core]
    fsmonitor = true          ; overridden to false on Linux
    untrackedCache = true
    preloadIndex = true
    bigFileThreshold = 512m

[feature]
    manyFiles = true

[pack]
    windowMemory = 256m
    threads = 0
    compression = 9

[gc]
    auto = 256
    autoPackLimit = 20

[diff]
    algorithm = histogram

[index]
    version = 4
```

After init: `git update-index --index-version 4`

---

## Safe Backup Sequence (mandatory)
1. world.saveAllChunks(true, null)
2. world.disableLevelSaving = true
3. MCA explosion + git commit (background thread)
4. world.disableLevelSaving = false

---

## Git Pull on Launch (Forge Loading Screen)

Uses `ProgressManager` — the public FML API used by JEI and other major mods.
Pull runs synchronously in `FMLPreInitializationEvent` so the loading screen is
the natural waiting UI.

```java
ProgressBar bar = ProgressManager.push("yugetGIT: Pulling " + worldName, 4);
bar.step("Connecting to remote");
bar.step("Receiving objects (14.2 MB) [" + worldName + "]");
bar.step("Resolving deltas");
bar.step("Updating working tree");
ProgressManager.pop(bar);
```

Parse git stderr for `"Receiving objects"`, `"Resolving deltas"`, `"Writing objects"`
progress lines. Extract MB values for the step label display.

### Pull config
```
pullOnLaunch = true
pullTimeoutSeconds = 300
pullRemote = "origin"
pullBranch = "main"
```

---

## Git Push

Push runs on BackgroundExecutor, reports progress via in-game chat messages.
```
"yugetGIT: Pushing... 45% (3.2 MB / 7.1 MB) [MyWorld]"
"yugetGIT: Push complete. 7.1 MB pushed to origin/main [MyWorld]"
"yugetGIT: Push failed. Not configured — see /yu backup status"
```

### Push config
```
pushOnExit = false
pushAfterCommits = 0
pushTimeoutSeconds = 300
pushRemote = "origin"
pushBranch = "main"
```

---

## MCA Explosion Pipeline

### Staging layout (PER_WORLD mode)
```
.minecraft/yugetGIT/worlds/<worldName>/staging/
    region/
        r.0.0/c.0.0.nbt ...
    DIM-1/region/
    DIM1/region/
    screenshots/
    meta/
        modlist.json
        players.json
```

### Explosion (ChunkExploder.java)
```
for each .mca:
    read via MCAUtil.readMCAFile(path)
    for each chunk (x,z):
        if null: skip
        if timestamp > ChunkTimestamp.get(region, x, z):
            write .nbt to staging
            ChunkTimestamp.update(...)
```

### Querz/NBT API
```java
MCAFile mca = MCAUtil.readMCAFile(path);
Chunk chunk = mca.getChunk(x, z);
MCAUtil.write(mca, outputPath);
```

Assembly (ChunkAssembler.java): reverse. Round-trip must be byte-identical — tested.

---

## Periodic Maintenance
Every 10 commits (background thread):
```
git gc --auto
git maintenance run --task=commit-graph
git maintenance run --task=incremental-repack
```

---

## Project Structure
```
src/main/java/com/yugetGIT/
    core/
        git/
            GitBootstrap.java           # OS+arch detection, download, PATH resolve
            GitExecutor.java            # ProcessBuilder wrapper, timeout, env vars
            GitCredentialChecker.java   # check user.name, user.email, credential.helper
            GitPullOperation.java       # pull + ProgressManager loading bar
            PushOperation.java          # push + chat progress reporter
            CommitBuilder.java          # safe save-off/save-on sequence
            RepoConfig.java             # write .git/config on init
            RepoModeManager.java        # PER_WORLD vs SINGLE_REPO, branch switching
        mca/
            ChunkExploder.java
            ChunkAssembler.java
            ChunkTimestamp.java
        config/
            yugetGITConfig.java         # all config fields + load/save
            StateProperties.java        # firstRun flag, resolved git path
        util/
            BackgroundExecutor.java
            OsDetector.java
            PlatformPaths.java
            ProgressParser.java         # shared stderr parser (push + pull)
            RepoNameSanitizer.java      # world name → valid GitHub repo name
    prelauncher/
        PreLaunchGitDialog.java         # Swing JOptionPane first-run check
        DownloadProgressDialog.java     # Swing JDialog with JProgressBar
    events/
        WorldSaveHandler.java
        ServerStopHandler.java
        PlayerDeathHandler.java
    commands/
        BackupCommand.java
        SaveSubcommand.java
        RestoreSubcommand.java
        ListSubcommand.java
        BranchSubcommand.java
        CheckoutSubcommand.java
        MergeSubcommand.java
        PushSubcommand.java
        PullSubcommand.java
        StatusSubcommand.java           # /yu backup status — shows credential state, config
        MigrateSubcommand.java          # /yu backup migrate — switch repo modes
    ui/
        TimelineGui.java
        CommitNode.java
        BranchSelector.java
        RestorePreview.java
        DiffOverlay.java
        SettingsPanel.java              # gear icon → repo settings (rename, mode, creds)
    mixin/
```

---

## Code Rules

### Naming
- Never: data, result, temp, obj, val, info, stuff, thing, item
- Names describe intent: stagedChunkPath, pendingCommitMessage, resolvedGitBinary
- Methods are verbs: explodeRegionFile(), downloadMinGit(), checkCredentialHelper()
- Booleans: isGitAvailable(), hasCredentialsConfigured(), isFirstRun()

### Style
- No emojis: code, comments, commit messages, logs, docs
- Minimal comments — only non-obvious logic
- Prefer functional style: pure functions, immutable data; stateful classes only when
  statefulness is the point
- No dead code after every task
- One responsibility per file

### Quality
- Methods max ~30 lines — split if longer
- No external dependencies unless justified:
  - Querz/NBT: justified
  - Swing: already in JDK, no dependency needed
  - Nothing else without discussion
- Read all relevant files in full before making changes
- Scan for dead code and optimization opportunities after every task

### Testing
Write and pass tests before moving to next stage.
Tests: src/test/java/com/yugetGIT/

Critical test cases:
- OsDetector: aarch64/arm64 normalize to same enum
- GitBootstrap: correct binary per OS+arch (mock System.getProperty)
- GitCredentialChecker: detects missing user.name, missing helper, auth failure
- GitExecutor: timeout kills process, stderr captured, non-zero exit throws
- GitPullOperation: stderr parsed to MB + percentage correctly
- PushOperation: chat messages formatted correctly, null player handled
- ProgressParser: all three git progress formats parse correctly
- ChunkExploder: changed chunks found by timestamp, unchanged skipped
- ChunkAssembler: round-trip byte-identical
- ChunkTimestamp: persists across restarts, handles missing entries
- RepoNameSanitizer: spaces→hyphens, uppercase→lowercase, invalid chars stripped
- RepoModeManager: branch name derived from world name correctly
- StateProperties: firstRun=false persisted after first-run dialog completes

### Architecture
- core/ has zero Forge imports — pure Java only
- prelauncher/ has zero Forge imports — pure Java + Swing only
- events/, commands/, ui/ call core/ — never the reverse
- BackgroundExecutor is the only place threads are created
- Config loaded once at startup, injected — never re-read mid-execution
- All path construction in PlatformPaths.java only
- ProgressParser shared between pull and push — never duplicated

### UI (vanilla Minecraft 1.12.2)
- Dirt background: GuiScreen.drawDefaultBackground()
- Vanilla GuiButton, GuiTextField, FontRenderer
- Screenshots as GL textures: GlStateManager + Tessellator + VertexBuffer
- No flat/modern aesthetic

---

## Development Stages

**Stage 1 — Pre-Launch Dialog + Bootstrap + Core Git**
- PreLaunchGitDialog + DownloadProgressDialog (Swing, fires before FML)
- OsDetector, PlatformPaths, StateProperties
- GitBootstrap (MinGit download + PATH resolve + version validate)
- GitExecutor (ProcessBuilder, timeout, env vars)
- RepoConfig (write .git/config, per-platform fsmonitor)
- CommitBuilder (save-off/save-on sequence)
- ChunkExploder + ChunkAssembler + ChunkTimestamp
- BackgroundExecutor, ProgressParser
- GitPullOperation (ProgressManager loading bar)
- PushOperation (chat progress)
- GitCredentialChecker (user.name, user.email, credential.helper checks)
- WorldSaveHandler + ServerStopHandler
- yugetGITConfig: all options
- Commands: /yu backup save, list, restore, status, /yu push, /yu pull
- Tests: all critical test cases

**Stage 2 — Repo Mode + Settings UI**
- RepoModeManager: PER_WORLD and SINGLE_REPO modes, branch switching
- RepoNameSanitizer
- MigrateSubcommand
- SettingsPanel: repo mode toggle, repo rename field, remote URL, cred status, toggles
- Tests: branch naming, mode switching, name sanitization

**Stage 3 — Branching**
- /yu backup branch, checkout, merge
- Branch metadata in /yu backup list
- Tests: branch create, checkout, merge conflict

**Stage 4 — Timeline UI + Screenshots**
- Auto-screenshot per commit (PNG blob in staging/screenshots/)
- TimelineGui: scrollable timeline, CommitNode per commit
- CommitNode: thumbnail, message, timestamp, author
- Click: RestorePreview, branch from here, details
- Tests: missing screenshot handled without crash

**Stage 5 — Advanced**
- Periodic maintenance (gc, commit-graph, incremental-repack every 10 commits)
- DiffOverlay: changed chunk grid between commits
- Modlist snapshot per commit
- Server support: online players per commit
- Time-lapse: screenshot playlist .txt export

---

## Version-Specific Notes (1.12.2 / Cleanroom)
- World save: net.minecraftforge.event.world.WorldEvent.Save
- Server stop: net.minecraftforge.fml.common.event.FMLServerStoppingEvent
- PreInit: net.minecraftforge.fml.common.event.FMLPreInitializationEvent
- Player death: net.minecraftforge.event.entity.living.LivingDeathEvent
- save-all: world.saveAllChunks(true, null)
- save-off/on: world.disableLevelSaving = true/false
- Chat: player.sendMessage(new TextComponentString("..."))
- Chunk DataVersion 1.12.2 ≤ 1343
- Mixin: Cleanroom pipeline, use sparingly, document every target
- GUI: GuiScreen, GuiButton, GuiTextField, Minecraft.getMinecraft().fontRenderer
- GL: GlStateManager, Tessellator.getInstance(), VertexBuffer
- Swing: available pre-LWJGL — use only in prelauncher/ package

---

## Libraries
- Querz/NBT 6.1 (JitPack): MCA read/write — only external dependency
- Swing/AWT: JDK built-in, no dependency entry needed
- No other libraries without explicit justification

---

## Environment
- IDE: VS Code
- Launcher: Prism Launcher
- AI: GitHub Copilot
- Place at: .github/copilot-instructions.md
- VS Code: "github.copilot.chat.codeGeneration.useInstructionFiles": true
## Testing Protocol
After each feature fix or implementation, I must always ask the user whether it works as expected before moving on.

When asking the user to test a feature, ALWAYS ask using the `vscode_askQuestions` tool to show the 'little message that u show for choosing between options' so the user can easily respond from the UI menu (THIS IS ALWAYS AND A NON-NEGOTIABLE).

Also, specify exactly what needs to be tested in the request.

During testing, I must continually tail the logs (`logs/latest.log` or `./run/logs/latest.log`) to capture errors, stack traces, and chat interactions so that fixes can be applied directly. I will ALWAYS post the crash/error messages directly to the chat so the user is informed of what went wrong before I apply the fixes.

## Improvement Proposal Protocol
When proposing new features, refactors, or architecture upgrades, I MUST use `vscode_askQuestions` before implementation.

Required flow:
1. Present candidate improvements (including user-provided ideas).
2. For each candidate, explain:
    - Benefits (what improves and why)
    - Downsides/Risks (complexity, regressions, maintenance cost, performance tradeoffs)
3. Ask the user, via `vscode_askQuestions`, whether to:
    - Implement selected ideas now
    - Keep selected ideas as backlog only
    - Add/replace with custom ideas
4. Do not implement any proposal until the user explicitly approves it in the question response.

This protocol is mandatory for both built-in ideas and any custom idea the user types.

---

## yugetGIT CLI Argument Standardization
ALL subcommands (`list`, `delete`, `restore`, etc.) MUST uniformly parse and accept the following universal argument tags:
- `-(number)`: Target the N most recent occurrences (e.g. `delete -4`).
- `-all`: Target ALL occurrences (e.g. `list -all` or wipe all repositories).
- `-start`: Reverses the target scope to the *earliest/first* occurrences (e.g. `list -start -5`).
- `-hash <id>`: Specifies an exact Git commit hash identifier for a targeted action.
- `-m "(message)"`: Parses a quoted string into a single string literal.

Use a unified `ParsedArgs` internal parser loop inside `execute` before routing any command logic.

## yugetGIT UI Feedback Standard
- Chat logs are for static status/error messages using `TextFormatting.GOLD` and standard formats.
- Chat prefix format for command output is mandatory: `[yugetGIT]  ` (two spaces after the closing bracket).
- Multi-line command responses must follow backup-list treatment: one prefixed header/status line, then detail lines without repeating the prefix.
- This formatting rule applies to all current commands and every new command/subcommand added in future tasks.
- Temporary process/progress feedback should prefer BossBar updates (stage/progress/countdown) and only fall back to Action bar when BossBar is not practical.
- BossBar text must contain only necessary content and should avoid repeating `[yugetGIT]` prefix spam.
- For restore flows, show reconnect countdown in BossBar before disconnecting players.

## /yu Command Guide Standard
The `/yu` command is the mandatory entrypoint before backup commands.

Required `/yu` subcommands:
- `/yu help`
- `/yu init`
- `/yu repo add <url>`
- `/yu backup <help|save|list|details|restore|worlds|status>`
- `/yu debug-dialog`
- `/yu fetch`
- `/yu push`
- `/yu pull`

Behavior rules:
- User must run `/yu init` after entering a world and before running `/yu backup` operations.
- Do not use OAuth/token storage in mod code; rely on system git credentials.
- `init` must initialize local repository state and switch/create a world branch (`world/<worldName>`).
- `repo add` must set/update local `origin` remote and accept full remote URLs (`https://...` and `git@...`).
- `backup list` should mirror the compact backup list output for quick commit browsing.
- `fetch` should download remote refs without changing local branch state.
- `push` and `pull` must target the active world branch and report clear success/failure chat lines.
- Remote repository creation is user-hosted; mod must only configure local git and explain this clearly.

---

## Documentation Requirement
ALWAYS create or update documentation mapping when writing complex systems like restoring branches, manipulating chunks, or introducing new commands. This keeps project context clean.
ALL user-facing command docs and "how to use the mod" docs MUST live under `docs/` as separated, focused markdown files (for example `docs/backup-commands.md`, `docs/getting-started.md`, `docs/debug-dialog.md`).
Do not put new command/how-to guides at repository root.
