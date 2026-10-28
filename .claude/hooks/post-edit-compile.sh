#!/usr/bin/env bash
# PostToolUse(Edit|Write): after a Java edit, compile right away so the agent sees errors in the same turn.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
input="$(cat)"
file="$(json_field "$input" '.tool_input.file_path')"
[[ "$file" == *.java ]] || exit 0

cd "${CLAUDE_PROJECT_DIR:-.}"
if ! out="$(mvn -B -q test-compile 2>&1)"; then
  echo "Compilation failed after editing $file:" >&2
  grep -E 'ERROR|error:' <<<"$out" | head -30 >&2
  exit 2   # PostToolUse can't undo the edit, but stderr is fed back to Claude to fix it now.
fi
exit 0
