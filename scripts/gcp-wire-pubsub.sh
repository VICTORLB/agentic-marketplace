#!/usr/bin/env bash
# Creates the authenticated push subscription offer-events → Cloud Run, with a dead-letter topic.
# Usage: PROJECT_ID=my-project ./scripts/gcp-wire-pubsub.sh https://marketplace-api-xxxx.a.run.app
set -euo pipefail
: "${PROJECT_ID:?set PROJECT_ID}"
URL="${1:?pass the Cloud Run service URL}"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
PUBSUB_AGENT="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"
PUSH_SA="marketplace-pusher@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud pubsub subscriptions create offer-events-push --project="$PROJECT_ID" \
  --topic=offer-events \
  --push-endpoint="${URL%/}/pubsub/push" \
  --push-auth-service-account="$PUSH_SA" \
  --push-auth-token-audience=marketplace-pubsub-push \
  --ack-deadline=30 \
  --dead-letter-topic=offer-events-dlq \
  --max-delivery-attempts=5 \
  --min-retry-delay=10s --max-retry-delay=300s

# The Pub/Sub service agent must be able to ack the source subscription when it forwards to the DLQ.
gcloud pubsub subscriptions add-iam-policy-binding offer-events-push --project="$PROJECT_ID" \
  --member="serviceAccount:${PUBSUB_AGENT}" --role=roles/pubsub.subscriber >/dev/null

echo "Push subscription ready → ${URL%/}/pubsub/push (audience: marketplace-pubsub-push)"
