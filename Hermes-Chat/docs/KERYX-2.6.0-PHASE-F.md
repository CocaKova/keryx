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

### 08-31 — the first walk found the wire was wrong (fixed)

The walk got an HTML page where JSON was promised: the Shipyard verbs rode the transport
seam (`GatewayCapabilities`), which aimed the direct door at its OWN REST base — a server
that never mounts `/keryx/git/…` — and left Matrix (no gateway seam at all) silently empty.
The routes live on the keryx payload surface (the Hermes Link base, bearer-authed), which
BOTH doors reach the same way.

Fix: `ShipyardRest` (`data/remote`) owns the wire — one client per configured (url, key),
rebuilt only when Link settings change; `ShipyardDelegate` builds it from the side-channel
settings and no longer takes the transport. The ten `shipyard*` verbs left
`GatewayCapabilities` / `GatewayRest` / `DirectTransport`; door gating is unchanged (the
`git` capability flag). `keryx.git.enabled` switched ON + gateway restarted 08-31 20:19 —
`/keryx/git/repos` answers 200 with the repo roster (verified 09-01).

## 6. Links become tappable (renderer 0.26.0 → 0.35.0, Kotlin 2.0.0 → 2.1.21)

Jonny 08-29: the Daily AI News Briefing's `[Read](https://arxiv.org/…)` links drew as plain
text and did nothing on tap. Trace: Hermes' Matrix adapter sends the raw markdown as `body`
(`_build_text_message_content`), `MatrixTransport.toMessage` keeps it, the parser leaves it
alone — the loss was in the renderer. multiplatform-markdown-renderer **0.26.0** hit-tests
links by hand inside its own `pointerInput` (`awaitFirstDown` → `getStringAnnotations("MARKDOWN_URL")`
→ `waitForUpOrCancellation` → `UriHandler.openUri`), and that detector loses to the bubble's
swipe-to-reply / `combinedClickable` (long-press + double-tap) gesture stack. The library
replaced it in **0.31.0** with Compose-native `withLink` / `LinkAnnotation.Url` ("Refactor to
use `withLink`", "Fix inline link might navigate wrong link", "Fix AUTOLINK in LINK").

Taken to **0.35.0** — the last release built on Kotlin 2.1 — which forces the toolchain to
**Kotlin 2.1.21 / KSP 2.1.21-2.0.2** (the KSP plugin is applied but nothing consumes it).
No source changes were needed: `markdownAnnotator`, `markdownComponents(codeBlock/codeFence)`,
`markdownColor`, `markdownTypography` and `GFMFlavourDescriptor` all still resolve. 555 tests
green, debug APK built (versionCode 70). Links now come underlined by the library's default
`TextLinkStyles`; bare URLs autolink via GFM.

⚠️ Walk item: open the Daily AI News Briefing room, tap a `[Read]` link → browser opens the
arXiv page; long-press and double-tap on the same bubble still react/pick. Check link colour
on both bubble styles.

Telegram-parity gaps that remain (not built): `||spoiler||` reveal, expandable blockquotes,
link-preview cards (needs a decision: phone-side fetch vs gateway unfurl), inline images
(renderer has no image loader wired).

⚠️ 09-01 walk finding: GFM AUTOLINKS (bare `https://…` URLs, e.g. the news brief's reddit
section) render underlined but are NOT tappable — no intent fires on a precise tap, while a
`[text](url)` link in the same bubble opens the browser. The 0.35 renderer evidently attaches
`LinkAnnotation` to markdown links only. Decide: post-process autolinks into links app-side,
or take the renderer's autolink handling upstream.

## Status
- 555 tests green (`:app:testDebugUnitTest` + `:core:jvmTest`), `assembleDebug` OK, versionCode 70.
- Fixed build (Shipyard wire) installed on the phone 09-01 07:14 over adb.
- **DEVICE-WALKED 09-01** (driven over adb against the live gateway, direct door):
  - Shipyard, end to end against a scratch repo + local bare remote registered as a
    temporary project (deleted after): repos roster → open → status counts + "in step" →
    WORKING TREE / BRANCH scope toggle (branch scope read "0 files changed", correct) →
    tracked diff → untracked all-add diff → STAGE from the diff panel (button flips to
    UNSTAGE, index verified server-side) → checkbox stage/unstage round-trip → commit sheet
    (staged count, RECENT subjects, push toggle) → "Committed 826d585 and pushed" toast,
    push landed on the remote's ref, ahead reset. PR line untested (no GH remote on scratch).
  - Projects: cards w/ session counts + captions → drill-in → New chat here (session
    created and ran in the project workspace) → long-press → Move to project… → toast, moved
    and moved back.
  - Model pill: catalog opens ("Reading the catalog…" → grouped providers, ⚡/💭 tags,
    ● current judged by name on qwen3.8-27b), selection verb fired (same-model re-pick).
    Cross-provider switch was already proven in the 08-31 evening walk (Haiku 4.5 answered,
    "model changed" back).
  - Archive on direct: sweep indexed the open session ("15 messages remembered"), search hit
    with highlight, tap → context view filled with the surrounding turns.
  - 2.5.7: failed execute_code run → theater row "1 failed", failure reason in error colour,
    `▸ output` fold shows the raw clipped result payload (`{"status":"error",…exit_code":1`).
    `/compress` command round-trips (preflight "Nothing to compress yet." on a short session).
    ⚠️ The in-flight 🗜 compaction label itself is still unseen — needs a genuinely long
    session; the status-cloud surface it rides demonstrably renders live SSE states.
  - Links (§6): `[Read]` in the Aug 31 news brief → Chrome foregrounded; long-press on the
    same bubble still opens the reaction picker. ⚠️ Bare-URL autolinks NOT tappable (above).
- Matrix-door walk items (model pick via `/model` room command, compaction label on a long
  Matrix turn) remain unwalked — the 09-01 walk drove the direct door.
- Release: `v2.6.0` is unblocked by the walk (decide first whether the autolink gap rides
  2.6.0 as a known gap or waits for a fix). Wake word = last Phase F item.
