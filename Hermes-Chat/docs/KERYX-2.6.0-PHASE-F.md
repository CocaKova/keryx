# Keryx 2.6.0 — Phase F, first two landings (2026-08-28)

Folds in the unreleased 2.5.7 (compaction on the cloud, `▸ output` on every row, execute_code on
Matrix — `docs/KERYX-2.5.7-COMPRESSION-AND-PAYLOADS.md`), E4 and the unattended-turn fix
(`9341263`). Adds the first two items of the 3.1 plan's Phase F list.

## 1. The Archive reaches the direct door

**Was:** `MainActivity` injected `null` for the indexer on direct, so the Archive opened, sat on
"waking the index…" forever, and `DirectTransport.messagesAround` returned nothing.

**Now — one store, two producers.** `ArchiveSweeper` (`progress` + `sweep`) is the seam the
`ArchiveDelegate` consumes; `ArchiveIndexer` (Matrix timeline walk) and the new
`RestArchiveIndexer` both implement it. `KeryxApp` picks by transport.

`RestArchiveIndexer` pages `/api/sessions/{id}/messages` newest-first (`TranscriptPages.pageUntil`)
until it crosses the ceiling (highest row id filed by an earlier sweep — reusing the store's
`catchupCeiling` slot) or runs dry, builds the stretch through `TranscriptBuilder`, and files what
`ArchiveIndexer.indexableEntries` admits. **Same policy as Matrix**: what the chat would fold into
a run card never enters the index. Overshoot to the page boundary is deliberate (a block cut
mid-run could misread a progress line as a bubble); the tail dedupes on `event_id`.

`messagesAround` on direct pages until the row is in hand with `before` rows beneath it, builds
the whole stretch (calls re-pair with results across page seams), cuts the window.

Verified live 08-28 against the gateway: pages are chronological inside, offset counts backwards
from the newest — the two facts the walk rests on.

### Traps
- ⚠️ The store is NOT re-keyed per door (no `ensureAccount` on direct). Matrix ids (`$…`/`!…`)
  and gateway ids (numeric / session ids) never collide, so both indexes coexist and a door
  crossing costs no backfill. A SECOND gateway on the same phone would collide on row ids —
  if that day comes, key the account by gateway URL and accept the wipe.
- ⚠️ Compression lineage: one logical chat = several session ids on the gateway. The Archive is
  per open room, so it shows the current segment only — the same thing the timeline shows.
- ⚠️ Client ids for synthesized rows are `tools-N` / `think-N`; `TranscriptPages.rowIdOf`
  reads the numeric suffix. Only prose rows are indexed, but the context view must cope with any.

## 2. The model catalog in the composer pill

**Was:** the pill's menu listed the Spire brains roster only — empty on this box, so it said
"No brains roster from the gateway" and did nothing.

**Now:** `ModelCatalog` (`:core`, parses the one payload shape both `model.options` RPC and
`GET /api/model/options` return; 4 tests) → `usable` = authenticated providers with models,
grouped in the menu, the session's live model marked ●, ⚡/💭 capability tags. The brains roster
follows below it when configured.

Two doors, two verbs, one `ModelDelegate`:
- **Direct**: `GatewayCapabilities.modelOptions(sessionId)` = RPC `model.options` with the
  session overlay (45 s timeout — it is on the gateway's long-handler pool);
  `selectModel` = RPC `config.set {key:"model", value:"<name> --provider <slug> --session"}`.
  Outcomes honoured: applied (toast + pill), **deferred** (turn in flight — lands next turn),
  **confirm_required** (expensive model — toast, tapping the same model again confirms).
- **Matrix**: catalog over Hermes Link; selection sends `/model <name> --provider <slug>` as a
  room command (the gateway scopes it to the room and redacts the command line ~5 s later).

### Traps
- ⚠️ `is_current` is true on MORE than one provider row at once (a dead `configured-current`
  row beside the serving `user-config` row). Current is judged by model NAME. Pinned by test.
- ⚠️ `/v1/models` only knows "hermes-agent" — never the catalog source.
- ⚠️ No `model.set` RPC exists; `config.set` with the raw `/model` grammar is the setter.

## 3. `react_to_message` tool row hidden
The reaction shows as the chip on the row it landed on; the call row said it twice. A FAILED
call keeps its row (same rule as failed deliveries). `ToolTheaterRow`, beside the delivery gate.

## 4. Projects — the gateway's native workspace grouping (direct door)

Harvested whole from Talaria (`ProjectsSpace`, `GatewayFolderField`, models, parsers, live
fixtures + tests): drawer door **Projects** (only once the gateway has answered `projects.tree`
— no dead doors), overview cards (explicit + auto-discovered repos, Home bucket filtered),
drill-in = flat recency-ordered session list with lane captions, **New chat here** (session born
with the project's cwd, opens directly), long-press → Archive / Delete (explicit projects),
New project with the gateway folder picker (`complete.path @folder:`; the folder is verified
to exist BEFORE the row is created — `projects.create` never looks at the disk).

Seam: eleven verbs on `GatewayCapabilities` (`projectsTree/projectSessions/projectsCatalog/
createProject/deleteProject/archiveProject/listFolders/folderExists/moveSessionToProject/
createSessionIn/adoptSession`); `ProjectsDelegate` in the ViewModel; `KeryxDest.Projects`.
`ChatViewModel.openSessionById(id, title)` adopts a session the roster does not carry.

**Move to project…** (`81abebd`): the drawer's session long-press menu lists the explicit projects
that have a folder (`projectMoveTargets`); picking one calls `session.workspace.move` — the only pin
the gateway has — and the session's lane caption follows on the next tree read. Direct door only.

### Traps
- ⚠️ A project is FOLDERS only — no prompt, skills, or model. Membership = cwd prefix. The
  gateway's `sessions` table has no `project_id`; `session.workspace.move` is the only pin.
- ⚠️ `complete.path`'s `text` is rebased on the gateway's completion cwd — only `display` is a
  name; hard 30-item cap, unannounced (`FolderPage.truncated`).
- ⚠️ Keryx's guard tests bit the port twice: hand-painted identity tints (→ `roomLight(id)`)
  and a raw `performHapticFeedback` (→ `LocalKeryxHaptics.press()`). Any future harvest from
  Talaria will trip the same two — run the suite before reading the screen.
- ⚠️ `projects_meta.active_id` on this box dangles (points at a deleted project) — the app
  ignores `active_id`, so nothing to do, but don't trust it as "the current project".

## 5. The Shipyard — git review and shipping from the phone (roadmap §2, "The Forge")

Renamed: the app already has a Skill Forge. Gateway half = `/keryx/git/…` in `keryx_stream.py`
(`_shipyard_routes`, a confined layer over `hermes_cli.web_git`, the library the dashboard's
`/api/git/*` wraps); app half = `ShipyardDelegate` + `ShipyardSpace`, drawer door **Shipyard**
gated on the new `git` flag in `/keryx/capabilities` (never on a 403 probe).

**What a phone can do:** pick a repo (every explicit project folder + discovered repos — only
git work trees), see branch / ahead / behind / counts, list changed files in two scopes
(working tree vs HEAD; branch vs merge-base), read any file's diff through the 2.4
`DiffPanel`, stage / unstage per file or all, commit (message + recent subjects as a
reminder, optional push in the same call), push. The PR line reads `review_ship_info`.

**What it deliberately cannot do (the roadmap's own traps):** revert (destroys work no git
object holds) and create-PR (opens a PR as the gateway's user). Both wait for a landing with
a confirm that names the file.

**Switch:** OFF by default. `keryx: { git: { enabled: true } }` in `~/.hermes/config.yaml` +
gateway restart. Off = every route answers 403 `shipyard_off`, the door does not appear.

### Traps
- ⚠️ `path` is confined to git work trees INSIDE the gateway user's home (`_shipyard_repo`).
  `web_git` itself confines nothing — do not bypass the helper for a "quick" route.
- ⚠️ Diffs are clipped at 2500 lines / 200 KB with `clipped` + `omittedLines`; the screen says
  so in amber. `review_diff` on an untracked file synthesizes an all-add diff via `--no-index`.
- ⚠️ `web_git` reads degrade to empty (`status: null` = not a repo any more); mutations raise
  `RuntimeError` → 409 `{code: "git"}` with git's stderr. A push failure AFTER a commit
  lands is reported the same way — the commit is already in.
- ⚠️ Kotlin nests block comments: writing `/keryx/git/*` inside KDoc opens a comment. Use `…`.
- ⚠️ `KeryxDest.Projects` was missing from `KeryxDest.all` (could not survive process death or
  deep-link) — fixed alongside `Shipyard`.
- Verified 08-28 by driving `_shipyard_routes` in-process against a scratch repo (off-gate,
  outside-home, not-a-repo, list, diff, untracked diff, stage, unstage, commit-context,
  commit, push-without-remote, ship-info, clip). NOT yet exercised through a live gateway —
  the payload is synced to both installed copies but the gateway was NOT restarted.

## Status
- 555 tests green (`:app:testDebugUnitTest` + `:core:jvmTest`), `assembleDebug` OK, versionCode 69.
- ⚠️ NOT device-walked (phone off adb 08-28 morning). Walk list, both doors: Archive on direct
  (open a deep session → Archive → search a word from an old turn → tap the hit → context view
  fills); pill → catalog lists silas-brain + any authenticated cloud → pick one → toast, pill
  relabels, next turn answers from it; Matrix: pick → `/model …` command lands, gateway confirms;
  plus the 2.5.7 walk (compaction label, `▸ output`).
- Projects walk: drawer → Projects (only if the door appears) → cards → drill-in → New chat here
  lands in the project's cwd → long-press a session → Move to project… → caption follows.
- Shipyard walk (after `keryx.git.enabled: true` + gateway restart): drawer shows Shipyard →
  pick a repo → toggle scope → open a file → stage → Commit… with push → toast + ahead
  resets → PR line if the branch has one.
- Release: cut `v2.6.0` after the walk (slow-cadence rule: this is a milestone, not a patch).
