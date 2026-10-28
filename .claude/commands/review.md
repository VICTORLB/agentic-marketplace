---
description: "Run the code-reviewer subagent on the current branch's diff"
---

Delegate to the `code-reviewer` subagent to review `git diff main...HEAD` (or the staged/working changes if the
branch has no commits yet). Return its findings table and verdict unchanged. Don't fix anything unless I ask.
