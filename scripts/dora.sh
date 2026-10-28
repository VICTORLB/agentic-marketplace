#!/usr/bin/env bash
# DORA snapshot from GitHub deployments (environment "production"). Needs: gh (authenticated), python3.
# Usage: ./scripts/dora.sh [days=30]
exec python3 "$(dirname "$0")/dora.py" "${1:-30}"
