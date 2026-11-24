#!/usr/bin/env bash
# Installs all IntelliJ IDEA plugins from this folder without network access (Linux, no GUI).
# For choosing plugins one by one, use ./plugin-installer.sh instead.
#
# Usage:
#   ./install-plugins.sh                  # install everything under plugins/
#   ./install-plugins.sh --optional       # also install plugins/optional/
#   ./install-plugins.sh --target DIR     # set the IDE plugin directory explicitly
#
# Default target: the newest existing ~/.local/share/JetBrains/IntelliJIdea<version> (or IdeaIC<version>)
# directory, otherwise ~/.local/share/JetBrains/IntelliJIdea2026.2.
# (If IDEA uses a custom -Didea.plugins.path, pass it with --target.)
# This script does not check plugin/IDE version compatibility; the graphical installer does.
# IntelliJ IDEA itself disables an incompatible plugin at startup.
#
# Close IntelliJ IDEA before running.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET=""
INCLUDE_OPTIONAL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --optional) INCLUDE_OPTIONAL=1; shift ;;
    --target) TARGET="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [ -z "$TARGET" ]; then
  DATA="${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains"
  NEWEST="$(ls -d "$DATA"/IntelliJIdea[0-9]* "$DATA"/IdeaIC[0-9]* 2>/dev/null | sed -E 's/.*(IntelliJIdea|IdeaIC)([0-9]{4}\.[0-9]).*/\2 &/' | sort -V | tail -1 | cut -d' ' -f2-)"
  TARGET="${NEWEST:-$DATA/IntelliJIdea2026.2}"
fi

command -v unzip >/dev/null || { echo "ERROR: 'unzip' not found (sudo apt install unzip / sudo dnf install unzip)." >&2; exit 1; }

if pgrep -f 'com.intellij.idea.Main|/bin/idea( |$)' >/dev/null 2>&1; then
  echo "WARNING: IntelliJ IDEA appears to be running. Close it and run again." >&2
  exit 1
fi

mkdir -p "$TARGET"
echo "Target directory: $TARGET"

install_zip() {
  local zip="$1"
  # top-level folder inside the zip (e.g. spotbugs-idea/)
  local top
  top="$(unzip -Z1 "$zip" | head -1 | cut -d/ -f1)"
  if [ -z "$top" ]; then echo "  SKIPPED (empty zip): $zip"; return; fi
  # remove an older version first so stale jars do not remain
  rm -rf "${TARGET:?}/$top"
  unzip -oq "$zip" -d "$TARGET"
  echo "  installed: $(basename "$zip")  ->  $top/"
}

TMPDIR_JOIN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_JOIN"' EXIT

# Large plugins are stored as <name>.zip.001, .002, ... (GitHub's 100 MB file limit).
# This joins the parts into a temporary zip and verifies it against <name>.zip.sha256.
joined_zip() {
  local base="$1" out
  out="$TMPDIR_JOIN/$(basename "$base")"
  cat "$base".[0-9][0-9][0-9] > "$out"
  if [ -f "$base.sha256" ]; then
    local expected actual
    expected="$(cut -d' ' -f1 < "$base.sha256")"
    actual="$(sha256sum "$out" | cut -d' ' -f1)"
    if [ "$expected" != "$actual" ]; then
      echo "  ERROR: joined parts of $(basename "$base") are damaged (SHA-256 mismatch)" >&2
      return 1
    fi
  fi
  echo "$out"
}

# Lists the plugin zips of a folder: real .zip files plus split ones (by their .001 part)
plugin_zips() {
  local f
  for f in "$1"/*.zip "$1"/*.zip.001; do
    [ -e "$f" ] || continue
    echo "${f%.001}"
  done
}

install_any() {
  local base="$1" zip
  if [ -f "$base" ]; then
    install_zip "$base"
  elif zip="$(joined_zip "$base")"; then
    echo "  joined $(ls "$base".[0-9][0-9][0-9] | wc -l) parts of $(basename "$base") (SHA-256 verified)"
    install_zip "$zip"
  fi
}

while IFS= read -r z; do install_any "$z"; done < <(plugin_zips "$HERE/plugins")

if [ "$INCLUDE_OPTIONAL" -eq 1 ]; then
  echo "Optional plugins:"
  case "$(uname -m)" in aarch64|arm64) ARCH=arm64 ;; *) ARCH=x64 ;; esac
  while IFS= read -r z; do
    # platform-specific packages carry the platform in their name (e.g. ...-windows-x64.zip)
    case "$z" in
      *-linux-"$ARCH".zip) ;;
      *-linux-*.zip|*-windows-*.zip|*-mac-*.zip) continue ;;
    esac
    install_any "$z"
  done < <(plugin_zips "$HERE/plugins/optional")
fi

echo
echo "Done. Start IntelliJ IDEA and check Settings > Plugins > Installed."
echo "Extra SpotBugs rule jars: $HERE/spotbugs-rules/spotbugs-4.8/"
