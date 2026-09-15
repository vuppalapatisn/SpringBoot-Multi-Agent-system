# CLAUDE.md — 09 Multi-Agent Supervisor

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| HTTP | `web/SupervisorController` |
| **The supervisor** | `supervisor/RefundSupervisor` ← start here |
| **The security model** | `authority/AgentRole` + `authority/AgentCapabilities` ← and here |
| Handoff contracts | `handoff/Handoffs` |
| Termination rules | `supervisor/RunLedger` |
| Agents | `agents/{Intake,Policy,Fraud,Payout}Agent` |
| The gate | `gate/GuardedPayout` |

## Invariants

1. **Only `PAYOUT` may hold an effectful capability.** `AgentCapabilities` fails startup otherwise,
   and `AgentAuthorityTest` fails the build. This is the project.
2. **`INTAKE` holds nothing.** It is the only agent that reads attacker-controlled text; giving it
   any capability, even a read, defeats the design.
3. **`PayoutAgent` has no `ChatClient`.** The agent with the dangerous capability has no prompt
   surface. If generated prose is needed on that path, generate it elsewhere and hand over the text.
4. **`FraudAgent` has no model** either — the question has a deterministic answer. Do not "upgrade"
   it to an LLM call.
5. **Handoffs are typed records with closed vocabularies.** No amount, no recipient, no tool name,
   no free-text field a downstream decision reads. Another agent's output is `R1`.
6. **The raw customer message goes only to `INTAKE`.**
   `rawMessageDoesNotCrossTheHandoff` enforces it.
7. **Intake flags are rules, not suggestions.** `requiresHuman()` stops the run before any
   specialist executes.
8. **`UNCLEAR` never pays.** Not from policy, not from intake.
9. **Cited clauses are verified** against the supplied list; a fabrication becomes `UNCLEAR`.
10. **The supervisor stays code.** If you make it a model, the routing agency budget goes from 0 to
    n, you inherit the ping-pong failure mode the `RunLedger` check is waiting for, and the CFG
    needs rewriting. That is a design decision, not a refactor.
11. **Every termination rule fails closed to `ESCALATED`.**
12. **The `runId` comes from the supervisor**, never from an agent — idempotency keys depend on it.

## Adding an agent

1. Add the role to `AgentRole` with the **smallest** set of allowed classes that works.
2. Add its capabilities to `MultiAgentConfig.agentCapabilities()` — startup validates them.
3. Define a typed handoff record in `Handoffs`. Closed vocabularies only.
4. Give it its own `ChatClient` bean if it genuinely needs a model; prefer none.
5. Add the handoff to `RefundSupervisor` with `ledger.handoffTo(role)` **before** the call.
6. Update `docs/CFG.md`: graph, authority map, handoff table, budgets.
7. Extend `AgentAuthorityTest` — if the new role can cause effects, reconsider the whole design.

## Tests

`mvn -q test` — 26 tests, no network.

`ScriptedChatModel` records every prompt, which is what makes taint assertions possible: check
`prompt(0)` (intake) against `prompt(1)` (policy). Model calls are consumed in handoff order —
intake first, then policy — so enqueue in that order. `@BeforeEach` resets the model and
`GuardedPayout`; one context per class.
