#!/usr/bin/env bash
# Starts the graphical plugin installer (Linux, offline).
#
# A separate Java installation is NOT required: the Java runtime bundled with
# IntelliJ IDEA (jbr/) is located and used.
#
# Usage:
#   ./plugin-installer.sh
#   ./plugin-installer.sh --idea-home /opt/idea-IU-262.xxxx     # IDEA installation directory
#   ./plugin-installer.sh --target ~/.local/share/JetBrains/IntelliJIdea2026.2  # plugin directory
#
# Environment variables: IDEA_HOME (IDEA installation directory), JAVA_HOME

set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/plugin-installer.jar"
[ -f "$JAR" ] || { echo "ERROR: $JAR not found." >&2; exit 1; }

IDEA_HOME_ARG=""
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --idea-home) IDEA_HOME_ARG="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done

is_idea_home() { [ -n "${1:-}" ] && [ -x "$1/bin/idea.sh" -o -x "$1/bin/idea" ] && [ -f "$1/product-info.json" ]; }

find_idea_home() {
  local candidate
  for candidate in "$IDEA_HOME_ARG" "${IDEA_HOME:-}"; do
    is_idea_home "$candidate" && { echo "$candidate"; return; }
  done
  # from an idea / idea.sh command on PATH
  for cmd in idea idea.sh intellij-idea-ultimate intellij-idea-community; do
    if command -v "$cmd" >/dev/null 2>&1; then
      candidate="$(dirname "$(dirname "$(readlink -f "$(command -v "$cmd")")")")"
      is_idea_home "$candidate" && { echo "$candidate"; return; }
    fi
  done
  # common installation locations, most recently modified first
  local list
  list="$(ls -dt \
      /opt/idea* /opt/intellij* /opt/JetBrains/* /opt/jetbrains/* \
      /usr/local/idea* /usr/local/intellij* /usr/share/idea* /usr/share/intellij* \
      "$HOME"/idea* "$HOME"/intellij* "$HOME"/Apps/idea* "$HOME"/apps/idea* "$HOME"/Programs/idea* "$HOME"/tools/idea* \
      "$HOME"/.local/share/JetBrains/Toolbox/apps/intellij-idea* \
      "$HOME"/.local/share/JetBrains/Toolbox/apps/*/ch-*/* \
      /snap/intellij-idea*/current \
      2>/dev/null)"
  while IFS= read -r candidate; do
    is_idea_home "$candidate" && { echo "$candidate"; return; }
  done <<< "$list"
}

java_version_ok() {
  local v
  v="$("$1" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+)(\.[0-9]+)?.*/\1/')"
  [ "$v" = "1" ] && return 1
  [ -n "$v" ] && [ "$v" -ge 11 ] 2>/dev/null
}

IDEA="$(find_idea_home)"
JAVA=""
if [ -n "$IDEA" ] && [ -x "$IDEA/jbr/bin/java" ]; then
  JAVA="$IDEA/jbr/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
fi

if [ -z "$JAVA" ] || ! java_version_ok "$JAVA"; then
  echo "ERROR: Java 11+ not found." >&2
  echo "Pass the IntelliJ IDEA installation directory, e.g.:" >&2
  echo "  $0 --idea-home /opt/idea-IU-262.xxxx" >&2
  echo "To install without a GUI: ./install-plugins.sh" >&2
  exit 1
fi

if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
  echo "ERROR: No graphical session found (DISPLAY is not set). To install without a GUI: ./install-plugins.sh" >&2
  exit 2
fi

echo "Java : $JAVA"
[ -n "$IDEA" ] && echo "IDEA : $IDEA" || echo "IDEA : not found (plugin directory will be guessed; you can change it in the window)"

EXTRA=()
[ -n "$IDEA" ] && EXTRA=(--idea-home "$IDEA")
# On HiDPI screens try: GDK_SCALE=2 ./plugin-installer.sh
exec "$JAVA" -Dawt.useSystemAAFontSettings=on -jar "$JAR" --root "$HERE" "${EXTRA[@]}" "${ARGS[@]}"
