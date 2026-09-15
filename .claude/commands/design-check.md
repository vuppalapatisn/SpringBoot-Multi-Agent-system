---
description: Run the CFG-first agentic design checklist against the current diff (or a named target)
argument-hint: "[project dir | PR number | branch] (default: current diff)"
---

Run a CFG-first agentic design review on: **$ARGUMENTS** (default: the uncommitted diff plus commits
on this branch vs. the default branch).

Use the `agentic-design-review` skill. Follow its procedure exactly.

Additional expectations for this repo:

1. If the change touches a project's tools, model calls, loops or effects, verify that
   `<project>/docs/CFG.md` was updated in the same diff. If it was not, that is a **blocking**
   finding — name the specific graph element that changed.
2. Verify the repo invariants from `CLAUDE.md`:
   - every `@Tool`/`@McpTool` method carries `@ToolBoundary`
   - irreversible tools are unreachable without an approval token
   - idempotency keys derive from `runId` + business key, never from model output
   - the model does not supply a value that could be looked up (refund amounts come from the order)
   - every loop has a numeric bound and a fail-closed exhaustion path
   - a durable checkpoint precedes every irreversible effect
3. Report the **agency budget** (count of model-chosen edges) before and after the change. An
   increase needs a justification in the diff.
4. End with the filled-in gate table from `docs/templates/DESIGN-REVIEW-TEMPLATE.md` Part B.

Be concrete: every finding gets a `file:line` and a failure scenario with specific inputs. Do not
pad the list.
