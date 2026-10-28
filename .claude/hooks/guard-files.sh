#!/usr/bin/env bash
# PreToolUse(Edit|Write): agents may never write secrets or loosen their own guardrails.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
input="$(cat)"
file="$(json_field "$input" '.tool_input.file_path')"
[[ -z "$file" ]] && exit 0

case "$file" in
  *.env|*.env.*|*/.env|*-key.json|*credentials*.json|*.pem|*.p12)
    echo "Blocked: $file looks like a secret. Agents never write credentials; use Secret Manager or env vars." >&2
    exit 2 ;;
  */.claude/settings.json|*/.claude/hooks/*)
    echo "Blocked: agents can't modify their own permissions or hooks ($file). Ask the human to change it." >&2
    exit 2 ;;
esac
exit 0
