#!/usr/bin/env bash
# One-time GCP bootstrap for agentic-marketplace. Safe to re-run (existing resources are skipped).
# Usage: PROJECT_ID=my-project GITHUB_REPO=owner/agentic-marketplace ./scripts/gcp-setup.sh
# Optional: REGION (default southamerica-east1 / São Paulo)
set -euo pipefail

: "${PROJECT_ID:?set PROJECT_ID}"
: "${GITHUB_REPO:?set GITHUB_REPO as owner/repo}"
REGION="${REGION:-southamerica-east1}"

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
PUBSUB_AGENT="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"

RUNTIME_SA="marketplace-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOYER_SA="marketplace-deployer@${PROJECT_ID}.iam.gserviceaccount.com"
PUSH_SA="marketplace-pusher@${PROJECT_ID}.iam.gserviceaccount.com"

try() { "$@" 2>/dev/null || echo "  (exists or skipped: ${*:1:4})"; }

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com pubsub.googleapis.com \
  firestore.googleapis.com iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com

echo "==> Artifact Registry + Firestore"
try gcloud artifacts repositories create marketplace --repository-format=docker --location="$REGION"
try gcloud firestore databases create --location="$REGION" --type=firestore-native

echo "==> Pub/Sub topics"
try gcloud pubsub topics create offer-events
try gcloud pubsub topics create offer-events-dlq
try gcloud pubsub subscriptions create offer-events-dlq-sub --topic=offer-events-dlq   # inspect dead letters here
gcloud pubsub topics add-iam-policy-binding offer-events-dlq \
  --member="serviceAccount:${PUBSUB_AGENT}" --role=roles/pubsub.publisher >/dev/null

echo "==> Service accounts (least privilege)"
try gcloud iam service-accounts create marketplace-runtime --display-name="Cloud Run runtime"
try gcloud iam service-accounts create marketplace-deployer --display-name="GitHub Actions deployer"
try gcloud iam service-accounts create marketplace-pusher --display-name="Pub/Sub push identity"

bind() { gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$1" --role="$2" --condition=None >/dev/null; }
bind "$RUNTIME_SA" roles/datastore.user
bind "$RUNTIME_SA" roles/pubsub.publisher
bind "$DEPLOYER_SA" roles/run.admin
bind "$DEPLOYER_SA" roles/artifactregistry.writer
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
  --member="serviceAccount:${DEPLOYER_SA}" --role=roles/iam.serviceAccountUser >/dev/null
# Lets Pub/Sub mint OIDC tokens as the push SA.
gcloud iam service-accounts add-iam-policy-binding "$PUSH_SA" \
  --member="serviceAccount:${PUBSUB_AGENT}" --role=roles/iam.serviceAccountTokenCreator >/dev/null

echo "==> Workload Identity Federation for GitHub Actions (no JSON keys)"
try gcloud iam workload-identity-pools create github --location=global --display-name="GitHub"
try gcloud iam workload-identity-pools providers create-oidc github --location=global \
  --workload-identity-pool=github --display-name="GitHub OIDC" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository=='${GITHUB_REPO}'"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER_SA" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/attribute.repository/${GITHUB_REPO}" >/dev/null

WIF_PROVIDER="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/providers/github"

cat <<OUT

Done. Set these GitHub repository variables (Settings → Secrets and variables → Actions → Variables), or run:

gh variable set GCP_PROJECT_ID --body "$PROJECT_ID"
gh variable set GCP_REGION     --body "$REGION"
gh variable set WIF_PROVIDER   --body "$WIF_PROVIDER"
gh variable set DEPLOYER_SA    --body "$DEPLOYER_SA"
gh variable set RUNTIME_SA     --body "$RUNTIME_SA"
gh variable set PUSH_SA        --body "$PUSH_SA"

Then merge to main (or run the Deploy workflow), and wire the push subscription:
  ./scripts/gcp-wire-pubsub.sh <cloud-run-url>
OUT
