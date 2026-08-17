#!/usr/bin/env bash
set -euo pipefail

# Refuses to let confidential material reach a PUBLIC repository.
#
# xdev-ai/ai-sdlc is public. A file or a sentence published by mistake is world-readable, and deleting it afterwards
# does not retract it: the content stays in git history, in every fork, in any clone a third party already took, and
# a deleted branch's commits remain reachable through the GitHub API. There is no undo.
#
# Two independent checks, because the first version of this gate had only the first one and was walked straight
# through:
#
#   1. PATHS  — nothing under local/ or private/ is tracked or staged.
#   2. TERMS  — no customer identifier appears in staged content or in a commit message that has not been pushed.
#
# The term check exists because the path check could not catch what actually happened: customer document codes were
# written into three commit messages and a pull request body while the path check passed, since a commit message is
# not a file.
#
# It is deliberately narrow. It matches an explicit list the operator maintains at local/confidential-terms.txt,
# not a heuristic for "confidential-looking" text. A scanner with false positives gets switched off, and one with
# false negatives is worse than none because it turns an unchecked risk into a checked box.
#
# It scans only unpushed commits. Already-published history cannot be un-published by a build gate, and a gate that
# fails forever is a gate that gets disabled.

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TERMS_FILE="local/confidential-terms.txt"
PROTECTED=(local private)

failures=0
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1" >&2; failures=$((failures + 1)); }
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; }
note() { printf '  \033[33m--\033[0m    %s\n' "$1"; }

printf '\n\033[1mConfidential-material gate\033[0m\n'

# --- 1. paths ----------------------------------------------------------------------------------------------------

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

staged_paths="$(git diff --cached --name-only 2>/dev/null | grep -E '^(local|private)/' || true)"
if [ -z "$staged_paths" ]; then
  pass "nothing confidential is staged"
else
  fail "staged for commit under a protected path:"
  printf '          %s\n' $staged_paths >&2
fi

if git check-ignore -q .env; then pass ".env is gitignored"; else fail ".env is NOT gitignored"; fi
if [ -z "$(git ls-files -- .env)" ]; then pass ".env is not tracked"; else fail ".env is TRACKED — rotate those credentials"; fi

# --- 2. terms ----------------------------------------------------------------------------------------------------

if [ ! -r "$TERMS_FILE" ]; then
  note "no $TERMS_FILE — term scanning skipped (create it to enable; it must stay gitignored)"
else
  if ! git check-ignore -q "$TERMS_FILE"; then
    fail "$TERMS_FILE is NOT gitignored — it lists the very strings being protected"
  fi

  terms="$(grep -vE '^[[:space:]]*(#|$)' "$TERMS_FILE" || true)"
  term_count="$(printf '%s\n' "$terms" | grep -c . || true)"
  if [ "${term_count:-0}" -eq 0 ]; then
    note "$TERMS_FILE has no terms yet"
  else
    scan() { # scan <label> <text>
      local label="$1" text="$2" hit=""
      while IFS= read -r term; do
        [ -z "$term" ] && continue
        if printf '%s' "$text" | grep -qiF -- "$term"; then hit="$hit $term"; fi
      done <<< "$terms"
      if [ -n "$hit" ]; then
        fail "$label contains customer identifiers:$hit"
        return 1
      fi
      return 0
    }

    staged_diff="$(git diff --cached 2>/dev/null || true)"
    if [ -z "$staged_diff" ]; then
      pass "nothing staged to scan for terms"
    else
      scan "staged changes" "$staged_diff" && pass "staged changes carry no customer identifier"
    fi

    upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
    [ -z "$upstream" ] && upstream="origin/main"
    if git rev-parse --verify -q "$upstream" >/dev/null; then
      unpushed="$(git log "$upstream..HEAD" --format='%H' 2>/dev/null | grep -c . || true)"
      if [ "${unpushed:-0}" -eq 0 ]; then
        pass "no unpushed commit to scan"
      else
        messages="$(git log "$upstream..HEAD" --format='%B' 2>/dev/null || true)"
        scan "$unpushed unpushed commit message(s)" "$messages" \
          && pass "$unpushed unpushed commit message(s) carry no customer identifier"
      fi
    else
      note "$upstream not found — commit-message scanning skipped"
    fi

    # A prepared message, when invoked as a commit-msg hook: verify-no-confidential.sh .git/COMMIT_EDITMSG
    if [ $# -ge 1 ] && [ -r "$1" ]; then
      scan "the prepared commit message" "$(cat "$1")" && pass "prepared commit message carries no customer identifier"
    fi
  fi
fi

# --- repository visibility ---------------------------------------------------------------------------------------

if command -v gh >/dev/null 2>&1; then
  visibility="$(gh api repos/xdev-ai/ai-sdlc --jq .visibility 2>/dev/null || echo unknown)"
  case "$visibility" in
    public)  pass "repository visibility confirmed public — this gate is load-bearing" ;;
    private) pass "repository is private; the gate still applies in case that changes" ;;
    *)       note "repository visibility could not be read (offline or unauthenticated)" ;;
  esac
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
  printf '\033[32mNo confidential material is tracked, staged, or named in an unpushed commit.\033[0m\n'
  exit 0
fi
printf '\033[31mConfidential-material gate failed with %s problem(s). Do not commit or push.\033[0m\n' "$failures" >&2
exit 1
