#!/usr/bin/env bash
# Runs before Gradle. In an overlay branch it first applies the branch patches (.vx-patch, .vx-patch2, ... in order) and
# checks the pushed files against the .vx-sha256 lists; then it downloads the AI models and the brand font.
# Montserrat: SIL Open Font License 1.1 (google/fonts, ofl/montserrat). The exit status is the
# font's: without the font the app uses the platform font.
#
# usage: ci-assets.sh <project_dir>
set -uo pipefail

DIR="$(cd "${1:?project dir}" && pwd)"
cd "$DIR"
NOTE="${GITHUB_STEP_SUMMARY:-/dev/null}"

for PATCH in .vx-patch*; do
  [ -f "$PATCH" ] || continue
  if patch -p1 --no-backup-if-mismatch < "$PATCH"; then
    echo "Overlay patch applied ($PATCH)." >> "$NOTE"
  else
    echo "::error::The overlay patch ($PATCH) did not apply."
    echo "### Overlay patch FAILED ($PATCH): the build below does not contain the patched changes" >> "$NOTE"
  fi
done
if [ -f .vx-sha256 ]; then
  # Later lists (.vx-sha256-3, ...) replace earlier entries for the same file.
  cat .vx-sha256* | awk 'NF == 2 { hash[$2] = $1 } END { for (path in hash) print hash[path] "  " path }' > "$DIR/.vx-sha.list"
  if sha256sum -c "$DIR/.vx-sha.list" > "$DIR/.vx-sha.log" 2>&1; then
    echo "Overlay files verified: $(grep -c ': OK$' "$DIR/.vx-sha.log") SHA-256 matches." >> "$NOTE"
  else
    {
      echo "### Overlay files differ from what was sent"
      echo '```'
      grep -v ': OK$' "$DIR/.vx-sha.log" | head -n 40
      echo '```'
    } >> "$NOTE"
  fi
fi

if [ -f .github/scripts/ci-models.sh ]; then
  if bash .github/scripts/ci-models.sh "$DIR"; then
    echo "AI models downloaded." >> "$NOTE"
  else
    echo "### AI models: download FAILED, this app is built without AI" >> "$NOTE"
  fi
fi

OUT="$DIR/app/src/main/assets/fonts"
BASE="https://github.com/google/fonts/raw/main/ofl/montserrat"
mkdir -p "$OUT"
curl -fsSL --retry 3 -o "$OUT/Montserrat-Variable.ttf" "$BASE/Montserrat%5Bwght%5D.ttf" || exit 1
curl -fsSL --retry 3 -o "$OUT/Montserrat-OFL.txt" "$BASE/OFL.txt" || true
MAGIC="$(head -c 4 "$OUT/Montserrat-Variable.ttf" | od -An -tx1 | tr -d '[:space:]')"
SIZE="$(wc -c < "$OUT/Montserrat-Variable.ttf")"
if [ "$MAGIC" != "00010000" ] || [ "$SIZE" -lt 100000 ]; then
  echo "::warning::Montserrat download is not a TrueType font; using the platform font."
  rm -f "$OUT/Montserrat-Variable.ttf"
  exit 1
fi
echo "Fonts: Montserrat variable ($SIZE bytes)"
