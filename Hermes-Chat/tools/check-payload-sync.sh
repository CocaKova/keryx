#!/usr/bin/env bash
#
# The gateway half of Keryx lives in three places at once, and they must be one file.
#
#   repo      hermes-plugin/keryx-stream/keryx_stream.py   ← the source of truth, what you edit
#   installed $HERMES_ROOT/gateway/keryx_stream.py         ← what install.py wrote, what runs
#   payload   $SILAS_EXT/payloads/keryx_stream.py          ← what a reapply puts back after an
#                                                            upgrade overwrites the installed copy
#
# The failure this exists to stop is not "someone forgot to copy a file". It is that the
# installed copy is *editable*, so a fix made against a running gateway lands in exactly one of
# the three, ships, and is then silently reverted by the next reinstall or the next `git pull`.
# It has happened in both directions.
#
# So: compare every copy that exists against the repo, and say which way the drift runs. A copy
# that is absent is not an error — a clone on a machine with no gateway is a normal thing — but
# it is reported, because "no drift" and "nothing to compare" are different answers.
#
# Usage:
#   tools/check-payload-sync.sh            # check, exit non-zero on drift
#   tools/check-payload-sync.sh --sync     # copy the repo version over every drifted copy
#
# Overrides (both default to the layout SILAS uses):
#   HERMES_ROOT=~/.hermes/hermes-agent
#   SILAS_EXT=~/.hermes/silas_ext

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$here/../hermes-plugin/keryx-stream/keryx_stream.py"

HERMES_ROOT="${HERMES_ROOT:-$HOME/.hermes/hermes-agent}"
SILAS_EXT="${SILAS_EXT:-$HOME/.hermes/silas_ext}"

sync=0
[[ "${1:-}" == "--sync" ]] && sync=1

if [[ ! -f "$repo" ]]; then
  echo "FAIL  the repo copy is missing: $repo" >&2
  exit 2
fi

# name → path, in the order a human thinks about them
copies=(
  "installed:$HERMES_ROOT/gateway/keryx_stream.py"
  "payload:$SILAS_EXT/payloads/keryx_stream.py"
)

repo_sum="$(md5sum "$repo" | cut -d' ' -f1)"
repo_lines="$(wc -l < "$repo")"

echo "repo      $repo_sum  ${repo_lines} lines"

drift=0
present=0

for entry in "${copies[@]}"; do
  name="${entry%%:*}"
  path="${entry#*:}"

  if [[ ! -f "$path" ]]; then
    printf '%-9s %s\n' "$name" "absent — not installed here, skipping"
    continue
  fi

  present=$((present + 1))
  sum="$(md5sum "$path" | cut -d' ' -f1)"

  if [[ "$sum" == "$repo_sum" ]]; then
    printf '%-9s %s  in sync\n' "$name" "$sum"
    continue
  fi

  drift=$((drift + 1))

  # Which way does it run? Lines only in the repo copy mean the repo is ahead; lines only in the
  # other copy mean the live file grew something the repo never got — the direction that loses
  # work, and the one that has actually happened.
  only_repo="$(diff "$repo" "$path" | grep -c '^<' || true)"
  only_other="$(diff "$repo" "$path" | grep -c '^>' || true)"

  printf '%-9s %s  DRIFT  (%s lines only in repo, %s lines only in %s)\n' \
    "$name" "$sum" "$only_repo" "$only_other" "$name"

  if [[ "$only_other" -gt 0 ]]; then
    echo "          ⚠ $name has $only_other lines the repo does not. Copying the repo over it"
    echo "            would DESTROY them. Diff it before you sync:"
    echo "            diff $repo $path"
  fi

  if [[ $sync -eq 1 ]]; then
    if [[ "$only_other" -gt 0 ]]; then
      echo "          refusing to --sync over a copy that is ahead; resolve by hand"
      continue
    fi
    cp "$repo" "$path"
    echo "          synced"
    drift=$((drift - 1))
  fi
done

echo

if [[ $present -eq 0 ]]; then
  echo "nothing to compare — no gateway on this machine"
  exit 0
fi

if [[ $drift -gt 0 ]]; then
  echo "FAIL  $drift of $present installed copies have drifted from the repo."
  echo "      Resolve before releasing: an APK built against the repo expects the repo's frames."
  exit 1
fi

echo "OK    all $present installed copies match the repo."
