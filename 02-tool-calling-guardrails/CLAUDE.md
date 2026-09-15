# CLAUDE.md — 02 Tool Calling with Guardrails

Read [`docs/CFG.md`](docs/CFG.md) before changing anything. Repo-wide rules:
[`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| HTTP | `web/RefundController`, `web/ApprovalController` |
| Model + tool loop | `service/RefundAgent` |
| Tool surface | `tools/RefundTools` |
| **The guard** | `gate/GuardedToolExecutor` ← start here |
| Policy tiers | `gate/PolicyGate` |
| Approvals | `gate/ApprovalStore`, `gate/PendingApproval`, `gate/FrozenEffectRunner` |
| Idempotency | `gate/IdempotencyKey`, `gate/IdempotencyLedger` |
| Classification | `boundary/ToolBoundary`, `boundary/ToolRegistry` |
| Startup validation | `config/GuardrailConfig` |

## Invariants — these are the point of the project

1. **Every `@Tool` method carries `@ToolBoundary`.** `ToolRegistry` throws otherwise, at startup.
2. **Every irreversible tool is listed in `agentic.tools.approval-required-tools`** or the
   application does not start.
3. **The guard is not an advisor and must not become one.** It sits inside the tool so a direct bean
   call cannot bypass it.
4. **No tool gains a value parameter** that could be looked up. `issueRefund(orderId)` — never
   `issueRefund(orderId, amount)`. No recipient parameter on `notifyCustomer`.
5. **`runId` comes from `ToolContext`**, never a tool parameter. Idempotency keys must not derive
   from anything the model can choose.
6. **No model turn between approval and execution.** `FrozenEffectRunner` executes the stored
   payload; do not add a "let the model continue" step.
7. **Expiry means no.** Never add an auto-approve-on-timeout path.
8. **A denial is not an escalation.** `GateOutcome.DENY` goes to a terminal state.
9. **`R1` results cross into enums.** `FraudService` must keep returning `FraudSignal`, never the
   partner's text.
10. **`DRY_RUN` performs no writes**, and there is a test that says so.

## Adding a tool — the checklist

1. Update `docs/CFG.md` first: node, class, and — if irreversible — a catalogue row.
2. Annotate with `@Tool` **and** `@ToolBoundary`.
3. Take identifiers; validate every argument; no raw SQL, path, URL or command parameters.
4. Route the effect through `guard.execute(context, effect, dryRun)`.
5. If irreversible: add it to `approval-required-tools`, give it a `PolicyGate` rule, and add a
   `FrozenEffectRunner` branch.
6. Add `spring.ai.tools.limits.max-calls-per-tool` for it.
7. Tests: no-token refusal, hash mismatch, dry-run, ceiling.

## Testing

`mvn -q test` — 38 tests, no network, no API key.

`ScriptedChatModel` **must** override `getOptions()` to return `ToolCallingChatOptions`; Spring AI
2.x decides tool-calling capability from the model's options type, and a plain `ChatOptions` makes
the tool loop silently skip. Do not "simplify" that override away.

Use `MutableClock`, not `Instant.now()`, for anything time-dependent: approval expiry and the
compensation window are both testable only because `Clock` is injected.

## Known limitation

`ApprovalStore` is in memory. Do not paper over this with a longer TTL or a retry — durable
approvals are project 07's job. If you need them here, the right change is to depend on a store, and
that is a design change requiring a CFG update.
