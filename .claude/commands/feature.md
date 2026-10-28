---
description: "Take a product idea from concept to PR using the planner \u2192 implementer \u2192 tester \u2192 reviewer pipeline"
argument-hint: "<product idea in plain words>"
---

Deliver this product idea end to end: **$ARGUMENTS**

Orchestrate the subagents in order. Pass each one the artifacts it needs: the spec path, the branch, and the
findings. Don't do their work yourself.

1. **Branch:** `git checkout -b feat/<short-slug>` from an up-to-date `main`.
2. **Plan:** delegate to `product-planner`. When it returns, show me the spec summary and its open questions,
   then **stop and wait for my approval**. Don't continue until I say the spec is approved (I may edit it first).
3. **Implement:** delegate to `implementer` with the approved spec path.
4. **Test:** delegate to `test-engineer` with the spec path. If it reports a production bug, send that back to
   `implementer`, then re-run `test-engineer`.
5. **Review:** delegate to `code-reviewer`. If the verdict is CHANGES REQUESTED, send the BLOCKER/MAJOR findings
   to `implementer`, then review again. Allow at most 2 review loops; after that, stop and summarize what's
   still open for me.
6. **PR:** once `mvn -B verify` is green and the review approves, push the branch and open a PR with
   `gh pr create`. The PR body must have: a link to the spec, a summary, an acceptance-criteria checklist, test
   evidence, known limitations, and the reviewer's verdict.

At the end, give me a short report: PR link, the number of review loops, and anything I should look at by hand.
