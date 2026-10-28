---
description: "Report DORA delivery metrics for this repo and suggest one improvement"
---

Delegate to the `release-engineer` subagent: run `./scripts/dora.sh $ARGUMENTS` (the argument is a window in days; it defaults to 30 when empty) and explain the results in
plain terms:
- deployment frequency
- lead time for changes
- change failure rate
- failed deployment recovery time

Compare them to the DORA performance bands, point out what limits us most, and propose one concrete change to
the workflow or pipeline.
