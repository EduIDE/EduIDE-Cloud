#!/usr/bin/env bash
# Check AGENTS.md's factual claims about paths, in both directions.
#
# Every AGENTS.md in this org had rotted into fiction. One named a CI job that
# had been deleted and a package.json path that does not exist; another
# described a landing page removed months earlier. Nothing checked them, so
# nothing noticed.
#
# A path in backticks must exist - unless the sentence says it does not, in
# which case it must NOT exist. That second direction matters: these docs
# deliberately name dead paths so nobody mistakes them for live code, and if
# someone later creates one the doc has quietly become wrong again.
#
# Only repo-relative, extension-bearing paths in backticks are checked. Prose is
# not validated; this is a lint, not a proof.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC="$ROOT/AGENTS.md"
[[ -f "$DOC" ]] || { echo "no AGENTS.md here"; exit 0; }

# A line asserting absence. Kept deliberately narrow so an ordinary sentence
# that happens to contain "no" does not silence a real check.
# Only true absence flips the check. "is dead", "retired" and "never built"
# describe something that exists and does not work, which is a different claim.
NEGATED='does not exist|do not exist|no longer exists|no longer exist|was removed|were removed|has no root'

interesting() {
  local p="$1"
  [[ "$p" == */* ]] || return 1          # must look like a path
  [[ "$p" == *.* ]] || return 1          # and carry an extension
  case "$p" in
    http*|*ghcr.io*|*github.com*|oci://*|*.tum.de*|*.io/*|*@*) return 1 ;;
  esac
  return 0
}

missing=0; resurrected=0; checked=0
while IFS= read -r p; do
  interesting "$p" || continue
  checked=$((checked + 1))

  # Every line mentioning this path; if any asserts absence, treat it as a
  # deliberate reference to something dead.
  if grep -nF -- "\`$p\`" "$DOC" | grep -qiE "$NEGATED"; then
    if [[ -e "$ROOT/$p" ]]; then
      echo "  now EXISTS but AGENTS.md says it does not: $p"
      resurrected=1
    fi
    continue
  fi

  if [[ ! -e "$ROOT/$p" ]]; then
    echo "  missing: $p"
    missing=1
  fi
done < <(grep -oE '`[A-Za-z0-9_./-]+`' "$DOC" | tr -d '`' | sort -u)

if [[ $missing -ne 0 || $resurrected -ne 0 ]]; then
  echo "AGENTS.md disagrees with the repository. Fix the doc or the path."
  exit 1
fi
echo "AGENTS.md: $checked referenced paths all check out"
