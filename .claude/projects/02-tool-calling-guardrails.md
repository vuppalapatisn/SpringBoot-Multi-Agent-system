# Brief — 02 Tool Calling with Guardrails

**Purpose:** the guardrail core. Projects 06-09 change the orchestration and reuse these ideas.

**Read first:** `02-tool-calling-guardrails/docs/CFG.md`.

**Start in:** `gate/GuardedToolExecutor` — every effect in the project passes through it.

**Do not break**
- `@ToolBoundary` on every `@Tool`; irreversible tools listed in `approval-required-tools`.
  Both are startup-enforced.
- The guard is not an advisor and must not become one.
- Tools take identifiers, never values. No amount parameter, no recipient parameter.
- `runId` comes from `ToolContext`; idempotency keys must not derive from model output.
- No model turn between approval and execution (`FrozenEffectRunner`).
- Expiry means no.

**Known limitation:** the approval store is in memory. Durability is project 07's job.

**Tests:** 38, including a model that asks for an ungated payout and is refused.
