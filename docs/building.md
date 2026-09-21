# Building

The toolchain, the modules, the ship gate and its verdicts, signing, and CI. The gate's own contract
is in `tools/README.md` at the repo root; this page summarizes it and does not duplicate the table.

## What is in the tree

| Directory | What it is |
|---|---|
| `Hermes-Chat/` | The Android app: two Gradle modules, `:app` and `:core` |
| `Hermes-Chat/hermes-plugin/keryx-stream/` | The in-tree copy of the gateway plugin. The standalone [keryx-stream](https://github.com/CocaKova/keryx-stream) repo is the one to install |
| `tools/` | `ship.sh` (the gate) and `remote-ship.sh` (its build-host twin) |
| `Hermes-Chat/docs/` | Internal build plans and pass notes, design history. Not the user docs |

`:core` is the KMP module both transports stand on. Its `commonMain` compiles against the common
stdlib only, so the compiler enforces the boundary: no `android.*`, no `java.*`, no Trixnity, no
OkHttp. A model or parser that lands there is one the Matrix path and the direct path can both use.
It targets jvm (what the app consumes) plus the iOS pair, which build only on a macOS host.

`:app` is the Compose application, `namespace` and `applicationId` `chat.keryx.app`, on JDK 17 with
Kotlin 2.1.21 and compile/target SDK 36. `minSdk` is 26, not 24: the app uses `java.time` and named
regex groups, and below O those are not degraded features, they are `NoClassDefFoundError` at the
call site with a green test suite.

The debug variant carries an `.debug` application-id suffix and a `-debug` version suffix, so it
coexists with a release install instead of replacing it, and a canary run cannot overwrite the app in
your pocket.

## Versions, from the source

| Thing | Value |
|---|---|
| `versionName` / `versionCode` | `2.11.8` / `99` |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 26 |
| Kotlin | 2.1.21 |
| JDK for the build | 17 |

## The ship gate

One verb, and it ends in a verdict block meant to be read by a model as much as a person.

```bash
tools/ship.sh              # unit tests + debug APK
tools/ship.sh --smoke      # ... plus the on-device canary (installs on the target)
tools/ship.sh --release    # ... assemble release instead of debug
tools/ship.sh --detach     # background; poll Hermes-Chat/build/ship/status
```

Three verdicts, and the third is the whole reason the file exists.

| Verdict | Meaning | What to do |
|---|---|---|
| `GREEN` | every stage asked for passed | ship it |
| `RED` | a stage failed, the code is wrong | read the log, fix, re-run |
| `AMBER` | a stage could not run (no device, no network): nothing was learned | do not revert, do not ship |

Exit codes are `0` / `1` / `3`. `AMBER` is not `RED` and not `GREEN`: a collapsing agent that reads
it as `RED` reverts good work when a phone is asleep, and one that reads it as `GREEN` ships work
nothing tested. `--smoke` installs the debug variant over what is on the connected device, which is
why it is off by default.

## The canary

`app/src/androidTest/.../canary/` is not a UI suite. It is a crash gate for the class of bug the JVM
tests here are structurally unable to see, because it needs Android to fail. Two named ones:

- ICU rejects a regex pattern the JVM accepts (a bare `}` in a character class), a class-initializer
  throw, and every rendered message kills the app (`fd8bf29`).
- Nested horizontal scrollers: the highlighter's own code composable wrapped its text in a second
  `horizontalScroll` inside Keryx's, and Compose refuses the infinite width at measure time
  (`e1ae954`).

`RenderCorpus` is generated, not enumerated: it walks `CodeHighlighting.knownTags`, so a grammar or
alias added to the app is covered the day it lands.

There is no emulator. Google ships the Android emulator for x64 and macOS arm64 only, and this host
class is arm64 Linux, so `--smoke` needs a real phone on adb. That is what `AMBER` is for.

## Signing

Release signing reads four keys from an uncommitted `Hermes-Chat/local.properties`:

```
keryx.keystore=/absolute/path/to/release.keystore
keryx.keystore.password=…
keryx.key.alias=…
keryx.key.password=…
```

Absent them, a release build falls back to the debug certificate by design, so a release APK sideloads
over a debug one without an uninstall. Two traps around this: an APK signed by a *different* keystore
still collides, and the fix is the matching file, not an uninstall (an uninstall wipes app data).
Second, the build-host flow carries the signing key with the build so the artifact a test ran against
is the artifact that ships.

## CI

`.github/workflows/ci.yml` runs on pushes to any branch, PRs, and manual dispatch, with
cancel-in-progress per ref. One job, `app`: checkout, Temurin JDK 17, the Android SDK, cached Gradle,
then the unit tests and the debug build.

One line there is worth knowing when a runner goes red on setup: the SDK action is pinned to
`packages: "platform-tools"`. Its default (`tools platform-tools`) names an obsolete package the
runner's current `sdkmanager` no longer lists, and the step died in seconds, before Gradle, with
`Failed to find package 'tools'`.

## Environment notes for this project

- Source the build environment before any Gradle invocation, but use the host's `/usr/bin/adb`: the
  PATH `adb` in that environment is an x86_64 binary that cannot run on this host. `ship.sh` already
  does this.
- The canonical checkout is `~/workspace/keryx`. `~/workspace/keryx-dev` is not a git repo and is
  stuck at an old `versionName`; building there wastes an hour.
- The Gradle configuration cache is on. A cached build can pass where a clean one fails, so on a
  failure that smells environmental, re-run with `--no-configuration-cache` before believing it.
- JVM args live in `gradle.properties`: 2048 MB heap, build cache on, configuration cache on.
