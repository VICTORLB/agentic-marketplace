# agentic-marketplace

Marketplace offers service (Java 21, Spring Boot 3.5) running on GCP Cloud Run with Firestore and Pub/Sub.
Built agent-first: a human product engineer owns the requirements and the merge button, while Claude Code
subagents plan, implement, test and review.

## Commands

- Build + all tests: `mvn -B verify`
- Unit tests only (fast): `mvn -B -q test -Dtest='*ServiceTest,*HandlerTest'`
- Run locally (in-memory adapters, no cloud): `mvn spring-boot:run` → http://localhost:8080
- Run against GCP: `SPRING_PROFILES_ACTIVE=gcp mvn spring-boot:run` (needs `gcloud auth application-default login`)
- DORA snapshot: `./scripts/dora.sh`

## Architecture (hexagonal)

```
adapter/in/web      → REST + Pub/Sub push endpoints (Spring MVC)
application         → use cases (MarketplaceService, OfferEventHandler). NO Spring/GCP imports.
application/port    → interfaces the use cases depend on
domain              → immutable records + business rules. NO framework imports.
adapter/out/memory  → default profile (local + tests)
adapter/out/gcp     → "gcp" profile (Firestore, Pub/Sub, OIDC push verification)
config              → the only place that wires adapters to use cases
```

Rules:
- `domain` and `application` must compile with plain `javac`. Never add Spring, Jackson or Google imports there.
- New infrastructure goes behind a port with an in-memory adapter, so tests never need the cloud.
- Domain objects are immutable records; state transitions return new instances and enforce their own invariants.
- Map errors via `ApiExceptionHandler`: NotFound→404, Forbidden→403, BusinessRule→409, IllegalArgument→400.

## Non-negotiables (reviewers check these)

- **Idempotency:** any write a client can retry takes an `Idempotency-Key`; any Pub/Sub consumer de-duplicates by `messageId`.
- **At-least-once delivery:** consumers must be safe to run twice. Never assume exactly-once.
- **Cloud Run:** stateless instances, graceful shutdown under 10s, config from env vars, listen on `$PORT`.
- **Secrets:** never in code, logs, or prompts. Nothing reads `.env*` or `*-key.json`.
- **Tests:** every behaviour change ships with a test that fails without it. Test names describe behaviour.
- **Small PRs:** one concern per PR, ideally < 300 changed lines.

## Definition of done

1. Acceptance criteria from the spec in `docs/specs/` are each covered by a test.
2. `mvn -B verify` passes locally.
3. `code-reviewer` subagent has no BLOCKER findings left.
4. PR description links the spec and lists any known limitation.

## Agent workflow

Use `/feature <idea>` for new work. It runs: `product-planner` → human approves spec → `implementer` →
`test-engineer` → `code-reviewer` → fix loop (max 2) → PR. Use `/review` for an ad-hoc review of the
current diff, `/upgrade-deps` for dependency/patch upgrades, `/dora` for delivery metrics.

Humans own: approving the spec, merging, deploying to production, anything touching IAM or billing.

## Backlog

Seeded product ideas live in `docs/backlog.md`.
