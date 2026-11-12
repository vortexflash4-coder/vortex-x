#!/usr/bin/env bash
# Entry point of an overlay branch (src/*). The branch carries only the changed files; the
# real build script is taken from the project zip named in .vx-base and run from here.
set -euo pipefail
DIR="$(cd "${1:?project dir}" && pwd)"
BASE="$DIR/$(head -n 1 "$DIR/.vx-base" | tr -d '[:space:]')"
TMP="$(mktemp -d)"
unzip -q "$BASE" '*/.github/scripts/ci-build.sh' -d "$TMP"
REAL="$(find "$TMP" -name ci-build.sh | head -n 1)"
cp "$REAL" "$DIR/.github/scripts/ci-build.base.sh"
rm -rf "$TMP"
exec bash "$DIR/.github/scripts/ci-build.base.sh" "$@"
