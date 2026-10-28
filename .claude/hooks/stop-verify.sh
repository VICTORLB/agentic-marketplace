#!/usr/bin/env bash
# Stop: before Claude says "done", run the tests if source changed. Failing tests send it back to work.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
input="$(cat)"
[[ "$(json_field "$input" '.stop_hook_active')" == "true" ]] && exit 0   # avoid infinite loops

cd "${CLAUDE_PROJECT_DIR:-.}"
git diff --quiet HEAD -- src/ 2>/dev/null && exit 0   # nothing uncommitted under src/

if ! out="$(mvn -B -q test 2>&1)"; then
  summary="$(grep -E 'Tests run:|FAIL|ERROR' <<<"$out" | head -20)"
  reason="Tests are failing. Fix them before finishing:
$summary"
  if command -v jq >/dev/null 2>&1; then
    jq -n --arg r "$reason" '{decision:"block", reason:$r}'
  else
    python3 -c 'import json,sys; print(json.dumps({"decision":"block","reason":sys.argv[1]}))' "$reason"
  fi
fi
exit 0
