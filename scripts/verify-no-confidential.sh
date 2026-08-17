#!/usr/bin/env bash
set -euo pipefail

# Refuses to let confidential material become tracked in a PUBLIC repository.
#
# xdev-ai/ai-sdlc is public. A file committed by mistake is world-readable, and deleting it afterwards does not
# retract it: the content stays in git history, in every fork, and in any clone or cache a third party already took.
# There is no undo. That asymmetry is why this is a build gate and not a guideline.
#
# The check is deterministic on purpose. It asserts that the local-only paths are ignored and that nothing under them
# is tracked. It deliberately does not try to detect "confidential-looking content" by pattern, because a scanner
# that produces false positives gets disabled, and a scanner that produces false negatives is worse than none.

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1" >&2; failures=$((failures + 1)); }
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; }

# Paths that must never hold tracked files. Keep in step with the local-only block in .gitignore.
PROTECTED=(local private)

printf '\n\033[1mConfidential-material gate\033[0m\n'

for path in "${PROTECTED[@]}"; do
  if git check-ignore -q "$path/probe" 2>/dev/null; then
    pass "$path/ is gitignored"
  else
    fail "$path/ is NOT gitignored — add it to .gitignore before putting anything there"
  fi

  tracked="$(git ls-files -- "$path" | head -5)"
  if [ -z "$tracked" ]; then
    pass "no tracked file under $path/"
  else
    fail "tracked files exist under $path/ — these are published on a public repo:"
    printf '          %s\n' $tracked >&2
  fi
done

# A staged file under a protected path is the exact moment to stop, before it reaches a commit.
staged="$(git diff --cached --name-only 2>/dev/null | grep -E '^(local|private)/' || true)"
if [ -z "$staged" ]; then
  pass "nothing confidential is staged"
else
  fail "staged for commit under a protected path:"
  printf '          %s\n' $staged >&2
fi

# .env carries real credentials for whatever environment it points at.
if git check-ignore -q .env; then pass ".env is gitignored"; else fail ".env is NOT gitignored"; fi
if [ -z "$(git ls-files -- .env)" ]; then pass ".env is not tracked"; else fail ".env is TRACKED — rotate those credentials"; fi

# The repository's own visibility, so the assumption behind this gate stays verified rather than remembered.
if command -v gh >/dev/null 2>&1; then
  visibility="$(gh api repos/xdev-ai/ai-sdlc --jq .visibility 2>/dev/null || echo unknown)"
  case "$visibility" in
    public)  pass "repository visibility confirmed public — this gate is load-bearing" ;;
    private) pass "repository is private; the gate still applies in case that changes" ;;
    *)       pass "repository visibility could not be read (offline or unauthenticated)" ;;
  esac
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
  printf '\033[32mNo confidential material is tracked or staged.\033[0m\n'
  exit 0
fi
printf '\033[31mConfidential-material gate failed with %s problem(s). Do not commit.\033[0m\n' "$failures" >&2
exit 1
