#!/usr/bin/env bash
# Run this project's Gradle build using the Windows JDK/Gradle toolchain and the
# prepared Gradle home at D:\Library\.gradle (dependencies already downloaded).
#
# Usage: tools/gradlew.sh <gradle tasks/args...>
# Override the Gradle home with CITYLINES_GRADLE_HOME.
#
# Note on quoting: WSL -> Windows interop mangles embedded quotes, so this script
# deliberately uses the unquoted `set VAR=value& cd /d DIR& cmd` form (no spaces
# around '&', otherwise cmd keeps the trailing space in the value). Paths must
# therefore not contain spaces.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WIN_ROOT="$(wslpath -w "$ROOT")"
GRADLE_HOME_WIN="${CITYLINES_GRADLE_HOME:-D:\\Library\\.gradle}"

case "${WIN_ROOT}${GRADLE_HOME_WIN}" in
  *" "*) echo "error: paths containing spaces are not supported by this wrapper" >&2; exit 2 ;;
esac

cmd.exe /c "set GRADLE_USER_HOME=${GRADLE_HOME_WIN}& cd /d ${WIN_ROOT}& gradlew.bat $*"
