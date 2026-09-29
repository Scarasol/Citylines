#!/usr/bin/env bash
# Fetch pinned upstream sources for reference. Text only, via the Windows-side
# HTTP proxy (raw.githubusercontent.com resolves to a bogus IP from WSL directly).
#
# Usage: tools/fetch-upstream.sh <tree-json-url> <sha> <outdir> <path>...
set -euo pipefail

PROXY="${UPSTREAM_PROXY:-http://localhost:10809}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fetch() { # fetch <url> <outfile>
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  cmd.exe /c "curl.exe -s -m 90 -x ${PROXY} \"${url}\"" > "$out"
  if [ ! -s "$out" ]; then
    echo "FAILED: $url" >&2
    return 1
  fi
  printf '  %7d  %s\n' "$(wc -c < "$out")" "${out#"$ROOT"/}"
}

case "${1:-}" in
  tree)
    # fetch upstream file tree as json
    repo="$2"; sha="$3"; out="$4"
    fetch "https://api.github.com/repos/${repo}/git/trees/${sha}?recursive=1" "$out"
    ;;
  files)
    # fetch a list of paths from stdin relative to the repo src root
    repo="$2"; sha="$3"; outdir="$4"
    while read -r p; do
      [ -z "$p" ] && continue
      fetch "https://raw.githubusercontent.com/${repo}/${sha}/${p}" "$outdir/$p"
    done
    ;;
  *)
    echo "usage: $0 tree <repo> <sha> <out.json>" >&2
    echo "       $0 files <repo> <sha> <outdir>   (paths on stdin)" >&2
    exit 2
    ;;
esac
