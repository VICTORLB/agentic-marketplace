---
description: "Upgrade dependencies and patches on a branch, verify, and open a PR (release-engineer)"
argument-hint: "[optional scope, e.g. \"spring boot only\"]"
---

Delegate to the `release-engineer` subagent:

1. Create the branch `chore/deps-<yyyy-mm-dd>`.
2. Find outdated dependencies and plugins (`mvn -B versions:display-dependency-updates versions:display-plugin-updates`),
   plus the Dockerfile base images. Scope: "$ARGUMENTS". If that is empty, include everything, but only patch and minor versions.
3. Read the release notes for each bump and flag any breaking change.
4. Apply the upgrades, run `mvn -B verify`, and fix anything trivial the upgrade broke. If a fix isn't trivial,
   revert that bump and list it as a follow-up.
5. Open a PR with a table of each dependency (from → to), its risk, and the release-notes link.
