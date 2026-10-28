#!/usr/bin/env bash
# End-to-end smoke test against a running instance: health → listing → offer (with retry) → accept.
# Usage: ./scripts/smoke.sh https://marketplace-api-xxxx.a.run.app   (defaults to http://localhost:8080)
set -euo pipefail
URL="${1:-http://localhost:8080}"
RUN="smoke-$(date +%s)"

field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

for i in 1 2 3 4 5; do
  curl -fsS "$URL/actuator/health" | grep -q '"UP"' && break
  [ "$i" = 5 ] && { echo "health check failed"; exit 1; }
  sleep 3
done
echo "health: UP"

listing=$(curl -fsS -X POST "$URL/api/listings" -H "X-User-Id: $RUN-seller" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke test bike","priceCents":10000,"currency":"BRL"}' | field id)
echo "listing: $listing"

offer_body='{"amountCents":9000}'
offer=$(curl -fsS -X POST "$URL/api/listings/$listing/offers" -H "X-User-Id: $RUN-buyer" \
  -H "Idempotency-Key: $RUN" -H 'Content-Type: application/json' -d "$offer_body" | field id)
retry=$(curl -fsS -X POST "$URL/api/listings/$listing/offers" -H "X-User-Id: $RUN-buyer" \
  -H "Idempotency-Key: $RUN" -H 'Content-Type: application/json' -d "$offer_body" | field id)
[ "$offer" = "$retry" ] || { echo "idempotency broken: $offer != $retry"; exit 1; }
echo "offer: $offer (retry returned same id)"

status=$(curl -fsS -X POST "$URL/api/listings/$listing/offers/$offer/accept" -H "X-User-Id: $RUN-seller" | field status)
[ "$status" = "ACCEPTED" ] || { echo "accept failed: $status"; exit 1; }
echo "accept: $status"
echo "SMOKE OK"
