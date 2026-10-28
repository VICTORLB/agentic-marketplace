# agentic-marketplace

A small marketplace **offers service** (Java 21 · Spring Boot 3.5 · GCP Cloud Run · Firestore · Pub/Sub), built
with an **agent-first workflow**. I act as the product engineer: I own the requirements, the review gates and the
merge. Claude Code subagents plan, implement, test and review, and GitHub Actions carries the work to production.

The point of the repo is the *workflow*, not the size of the domain: how to get speed from coding agents without
giving up correctness, security or delivery stability (DORA).

## The domain

Sellers create listings and buyers place offers. A seller accepts one offer, which marks the listing **SOLD** and
rejects the other offers. Every state change publishes an event to Pub/Sub, and a push consumer handles it
idempotently.

| Endpoint | Notes |
|---|---|
| `POST /api/listings` | `X-User-Id` = seller |
| `GET /api/listings/{id}` | |
| `POST /api/listings/{id}/offers` | requires `Idempotency-Key`; a retry returns the original offer (200) |
| `GET /api/listings/{id}/offers` | |
| `POST /api/listings/{id}/offers/{offerId}/accept` | only the seller (403 otherwise) |
| `POST /pubsub/push` | Pub/Sub push endpoint: OIDC-verified on GCP, de-duplicated by `messageId` |

Hexagonal layout: `domain` and `application` are framework-free, and adapters are swapped by profile
(in-memory locally, Firestore/Pub/Sub on Cloud Run). See [CLAUDE.md](CLAUDE.md).

## The agent team

| Agent | Role | Can edit? | Model |
|---|---|---|---|
| `product-planner` | idea → spec with Given/When/Then + step plan (`docs/specs/`) | specs only | opus |
| `implementer` | approved spec → code + tests, test-first, small commits | `src/` | sonnet |
| `test-engineer` | maps acceptance criteria → tests, hunts edge cases | `src/test/` | sonnet |
| `code-reviewer` | skeptical review: correctness, idempotency, races, security, hallucinated APIs | **read-only** | opus |
| `release-engineer` | dependency upgrades, CI failures, pipeline, DORA | pipeline files | sonnet |

Slash commands: `/feature <idea>` (the full pipeline with a human approval gate), `/review`, `/upgrade-deps`, `/dora`.

### Guardrails: three layers, from soft to hard

1. **Context** (`CLAUDE.md`, agent prompts): architecture rules, non-negotiables, definition of done.
2. **Permissions** (`.claude/settings.json`): allow/ask/deny lists. Pushing and `gcloud` always ask, and secrets are unreadable.
3. **Hooks** (`.claude/hooks/`): deterministic checks that hold even if a prompt is ignored:
   - `guard-bash.sh` blocks force-push, pushes to main, `--no-verify`, `gcloud run deploy`, IAM changes, and `rm -rf`.
   - `guard-files.sh` stops agents from writing secrets or editing their own settings/hooks.
   - `post-edit-compile.sh` compiles after every Java edit and feeds errors back to the agent immediately.
   - `stop-verify.sh` runs the tests before the agent may say "done" if `src/` changed.

### CI/CD

| Workflow | What it does |
|---|---|
| `ci.yml` | `mvn verify` + an architecture rule (no framework imports in domain/application) |
| `claude-review.yml` | Claude reviews every PR with the `code-reviewer` checklist and posts inline comments |
| `claude.yml` | `@claude` in an issue or PR hands the task to Claude Code in Actions |
| `claude-upgrade.yml` | weekly: upgrades dependencies and opens a PR (toil removal) |
| `deploy.yml` | merge to main → build → Cloud Run → smoke test → **automatic rollback** on failure |

`./scripts/dora.sh` computes deployment frequency, lead time, change failure rate and recovery time from GitHub's
deployment records.

## Run it

```bash
mvn verify                      # build + tests
mvn spring-boot:run             # in-memory adapters on :8080
./scripts/smoke.sh              # end-to-end smoke against localhost
```

Hooks need `bash` plus `jq` or `python3` (on Windows, run Claude Code from Git Bash or WSL).

## Deploy to GCP (≈20 min, fits the free tier)

1. Create a GCP project with billing enabled (set a budget alert of ~US$5).
2. `gcloud auth login`, then
   `PROJECT_ID=<id> GITHUB_REPO=<you>/agentic-marketplace ./scripts/gcp-setup.sh`
3. Run the `gh variable set ...` lines the script prints. Add the `ANTHROPIC_API_KEY` secret for the Claude
   workflows (or run `/install-github-app` inside `claude`).
4. Push to `main` → the **Deploy** workflow publishes to Cloud Run (`southamerica-east1`).
5. `PROJECT_ID=<id> ./scripts/gcp-wire-pubsub.sh <service-url>` creates the authenticated push subscription with a DLQ.
6. `./scripts/smoke.sh <service-url>`

Cloud Run scales to zero (`min-instances=0`), so an idle demo costs close to nothing.

## Design notes and trade-offs (good interview material)

- **Idempotency everywhere:** client retries use `Idempotency-Key`, and Pub/Sub consumers de-duplicate by
  `messageId` through a Firestore transaction (Pub/Sub is at-least-once).
- **Publish after save, synchronously:** a failed publish fails the request rather than silently losing the
  event. The proper fix (transactional outbox) is backlog item 1.
- **Known race:** two concurrent `accept` calls can both read ACTIVE. This is backlog item 1 too, and it's the first
  thing the `/feature` pipeline is asked to fix.
- **Push auth:** the subscription signs an OIDC token as a dedicated SA with a fixed audience, and the app verifies
  the signature, the audience and the SA email.
- **Keyless CI:** GitHub → GCP uses Workload Identity Federation, so there are no JSON keys anywhere.
- **Cloud Armor** needs an external HTTPS load balancer with a serverless NEG in front of Cloud Run. It's left out
  to keep the demo free and is listed as a next step.

## Backlog

See [docs/backlog.md](docs/backlog.md). The workflow is explained in [docs/workflow.md](docs/workflow.md).

