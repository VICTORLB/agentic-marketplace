#!/usr/bin/env bash
# Shared helper: read a field from the hook's stdin JSON. Prefers jq, falls back to python3.
json_field() {
  local json="$1" path="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -r "$path // empty" <<<"$json"
  else
    python3 -c 'import json,sys
d=json.loads(sys.argv[1])
for k in sys.argv[2].lstrip(".").split("."):
    d=d.get(k) if isinstance(d,dict) else None
print("" if d is None else (str(d).lower() if isinstance(d,bool) else d))' "$json" "$path"
  fi
}
