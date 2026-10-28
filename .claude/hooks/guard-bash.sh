#!/usr/bin/env bash
# PreToolUse(Bash): deterministic guardrails that hold even if a prompt or subagent misbehaves.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
input="$(cat)"
cmd="$(json_field "$input" '.tool_input.command')"
[[ -z "$cmd" ]] && exit 0

block() { echo "Blocked by guard-bash: $1. Command: $cmd" >&2; exit 2; }

[[ "$cmd" =~ git[[:space:]]+push.*(--force|-f([[:space:]]|$)) ]] && block "force push is never allowed"
[[ "$cmd" =~ git[[:space:]]+push.*[[:space:]](origin[[:space:]]+)?(main|master)([[:space:]]|$) ]] && block "push to main goes through a PR"
[[ "$cmd" =~ --no-verify ]] && block "skipping git hooks is not allowed"
[[ "$cmd" =~ gcloud[[:space:]]+run[[:space:]]+(deploy|services[[:space:]]+(delete|update)) ]] && block "production changes go through the deploy workflow"
[[ "$cmd" =~ (add-iam-policy-binding|remove-iam-policy-binding|set-iam-policy) ]] && block "IAM changes are human-only"
[[ "$cmd" =~ rm[[:space:]]+-[a-zA-Z]*r[a-zA-Z]*f|rm[[:space:]]+-[a-zA-Z]*f[a-zA-Z]*r ]] && block "recursive force delete"
exit 0
