# CLAUDE.md — 04 MCP Server

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| MCP tools | `tools/RefundMcpTools` (adapter only) |
| **All policy** | `service/RefundDesk` ← start here |
| Human surface | `web/ApprovalAdminController` (plain HTTP, not MCP) |
| Server policy config | `config/ServerProperties` |

## Invariants

1. **No `approve*` MCP tool, ever.** The caller that requests an irreversible action must not be
   able to authorise it. `McpServerContractTest.approvalIsNotExposedOverMcp` enforces this.
2. **No tool takes an amount, a recipient, or any value that can be looked up.**
   `noValueParameters` enforces this.
3. **Policy lives in `RefundDesk`, not in the tool adapter.** `RefundDeskTest` runs with no MCP in
   the picture, and it should stay that way — if a rule is only testable through the transport, it
   is in the wrong class.
4. **Hints stay honest.** `destructiveHint = true` on `issueRefund`, `readOnlyHint = true` on the
   reads. Clients may use these to decide what needs confirmation; lying here harms someone else's
   system. Contract-tested.
5. **Descriptions stay factual.** No imperatives ("always call this first"), no claims about other
   tools. A description is text injected into someone else's model context.
6. **`R1` results cross into enums.** The fraud provider's free text never leaves `RefundDesk`.
7. **Idempotency keys are derived here**, from `orderId` + amount, and checked before anything
   irreversible.
8. **Reads keep working when the kill switch is on.** A switch that blinds the operator is not
   usable during an incident.

## Changing the published contract

Any change to the tool set, a tool name, a schema, or an annotation hint is a **breaking change for
clients that cached it** — the "rug pull" that project 05 defends against. So:

1. Update `docs/CFG.md`.
2. Update `McpServerContractTest` in the same commit (it exists to make the change deliberate).
3. Keep `tool-change-notification: true` so connected clients re-read rather than calling a moved
   schema.

## Tests

`mvn -q test` — 17 tests, no network, no model, no API key.

## Known limitations (fine here, not in production)

* State is in memory: approvals, receipts, rate-limit counters.
* No authentication on either surface. A real deployment needs staff auth on `/admin/**`, client
  credentials on `/mcp`, and a caller identity that means something — right now `"mcp-client"` is a
  constant, used only for rate limiting and audit, never for authorisation.
