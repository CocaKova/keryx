# tools/

## `ship.sh` — the gate

The thing that can say **no** without a human looking at a phone.

```bash
tools/ship.sh              # unit tests + debug APK
tools/ship.sh --smoke      # ... and the on-device canary (installs on the target)
tools/ship.sh --release    # ... assemble release instead of debug
tools/ship.sh --detach     # background; poll Hermes-Chat/build/ship/status
```

It ends in a verdict block. **Three verdicts, and the third is the point:**

| | meaning | what to do |
|---|---|---|
| `GREEN` | every stage asked for passed | ship it |
| `RED` | a stage failed — the code is wrong | read the log, fix, re-run |
| `AMBER` | a stage *could not run* — no device, no network | nothing was learned; **do not revert, do not ship** |

Exit codes match: `0` / `1` / `3`.

`AMBER` exists because an agent that collapses it into `RED` reverts good work when a phone
is asleep, and one that collapses it into `GREEN` ships work nothing has tested. Neither is
recoverable by prompting harder — the distinction has to live in the tool.

## The canary — `Hermes-Chat/app/src/androidTest/…/canary/`

Not a UI test suite. A **crash gate** for the class of bug that JVM tests here are
structurally unable to see, because it needs Android to fail:

- **ICU vs the JVM.** Android's regex engine rejects patterns the JVM accepts (a bare `}` in
  a character class). `MathUnicode`'s class-initializer threw on-device, and *every rendered
  message killed the app* — with a green test suite. (`fd8bf29`)
- **Nested horizontal scrollers.** The markdown renderer's own highlighted-code composable
  wraps its text in a `horizontalScroll`, inside Keryx's. Compose refuses the infinite width
  that hands the inner one at measure time, so every fence tagged with a grammar the
  tokenizer knew died as it scrolled into view. (`e1ae954`)

`RenderCorpus` is **generated, not enumerated** — it walks `CodeHighlighting.knownTags`, so a
grammar or alias added to the app is covered the day it is added. Add `"ps1" to "powershell"`
to the alias map and the corpus grows by one case with nobody remembering to come here.

### Why there is no emulator

Google ships the Android emulator for `linux-x64`, `macosx-aarch64/x64` and `windows-x64`
only. This host is arm64 Linux, so **`--smoke` needs a real phone on adb.** That is what
`AMBER` is for.

## Traps this project has actually hit

- `source ~/android-buildenv/env.sh` before any gradle invocation — but **that PATH's `adb`
  is an x86_64 binary that cannot run here.** Use `/usr/bin/adb`. `ship.sh` already does.
- Canonical checkout is `~/workspace/keryx`. `~/workspace/keryx-dev` is not a git repo and is
  stuck at versionName 1.0 — building there wastes an hour and produces nothing.
- The configuration cache is **on**. A cached build can pass where a clean one fails; when a
  failure smells environmental, re-run with `--no-configuration-cache` before believing it.
- There is no release keystore. Release builds fall back to the debug cert by design, so a
  release APK sideloads over a debug one without an uninstall.
