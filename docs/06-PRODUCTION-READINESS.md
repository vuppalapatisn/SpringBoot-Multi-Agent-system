# Production Readiness

The go-live gate. Everything here is a yes/no with an owner. "Mostly" is a no.

---

## 1. Security

- [ ] **Least authority** — the agent's credentials grant exactly the tools it has, nothing more.
      No admin keys, no wildcard IAM, no shared service account across agents.
- [ ] **Credential isolation per agent** (multi-agent) — only the payout agent holds a payment key.
- [ ] **Secrets** from the environment or a vault; never in `application.yml`, never in a prompt.
- [ ] **Prompt injection** — untrusted content is labelled as data, never concatenated into the
      system message; a taint validator sits on every path from `R1` to an irreversible argument.
- [ ] **Egress allowlist** — recipients, URLs and destinations validated against a list your code
      owns, not a list the model produces.
- [ ] **Data classification** — what may enter the context window is decided by policy, and PII is
      redacted before it does; the provider's data-retention terms are recorded and acceptable.
- [ ] **Tenant isolation** — vector store queries and memory are filtered by tenant, and there is a
      test that proves tenant A cannot retrieve tenant B's documents.
- [ ] **MCP servers** — per-server trust level, tool allowlist, tool-list hash pinned, change alerts.
- [ ] **No arbitrary execution tools** — no `runCommand`, `eval`, or raw `executeQuery`.
- [ ] **Rate limits** per tenant, per customer, and per irreversible tool.
- [ ] **Audit log** append-only, with no `UPDATE`/`DELETE` grant to the application role.

---

## 2. Correctness & safety

- [ ] The [four graph invariants](00-DESIGN-CHECKLIST.md#phase-1--draw-the-control-flow-graph-before-any-code)
      hold, verified against the committed CFG.
- [ ] Every irreversible action has an approval gate on every inbound path.
- [ ] Every approval is durable, frozen, attributed, bounded, replay-safe, legible, refusable.
- [ ] Idempotency keys are system-derived and passed downstream where the provider supports them.
- [ ] Every budget has a number, an enforcement point, and a fail-closed exhaustion path.
- [ ] Malformed model output, model refusal, and provider outage all have defined paths.
- [ ] Compensation implemented for every reversible effect, with its window as a timer.
- [ ] Kill switch (`agentic.tools.execution-mode`) changeable without a deploy, and tested.

---

## 3. Reliability

- [ ] Timeouts on every model call and tool call; no unbounded waits anywhere.
- [ ] Retries only on idempotent operations, with jitter and a retry budget.
- [ ] Circuit breaker per downstream dependency, including the model provider.
- [ ] Graceful degradation when the provider is down — documented, tested, and visible to users.
- [ ] Reconciliation sweeper for `PENDING` effects (crash between intent and effect).
- [ ] Pending-approval sweeper for expiries.
- [ ] Poison-run detection: a run that always fails stops retrying and raises an alert.
- [ ] Concurrency caps per tenant; back-pressure instead of collapse.
- [ ] Graceful shutdown: in-flight runs checkpoint rather than vanish.

---

## 4. Cost

- [ ] Per-run cost ceiling enforced in code (not just monitored).
- [ ] Cost attributed per tenant / feature and visible in a dashboard.
- [ ] p99 cost known and budgeted — for agent architectures, p99 is 5–10× p50.
- [ ] Prompt caching used where the system prompt or retrieved context is stable.
- [ ] Model tiering deliberate: the cheapest model that passes the evals for each node.
      (In this repo: `claude-sonnet-5` for classification and specialists, `claude-opus-5` only for
      planner/supervisor roles.)
- [ ] Context growth bounded — chat memory windowed, tool results size-capped, retrieved chunks
      limited by top-k *and* by total tokens.
- [ ] Alert when projected monthly spend deviates from plan.

---

## 5. Performance

- [ ] p50 / p95 / p99 latency measured per architecture and per node.
- [ ] Streaming used where a human is waiting.
- [ ] Parallel fan-out for independent reads (project 06 does policy ∥ fraud).
- [ ] Connection pools sized for the model provider; pool metrics enabled.
- [ ] Vector store index type and dimensions chosen deliberately; query latency monitored.
- [ ] Load test at 2× expected peak, including the tool dependencies.

---

## 6. Operability

- [ ] **Runbook** covering: how to find a run by id; how to flip the kill switch; how to revoke
      credentials; how to expire a stuck approval; how to reconcile a `PENDING` effect; who to call.
- [ ] Dashboards: runs by terminal status, cost, budget exhaustion, gate decisions, pending-approval
      age, tool error rates.
- [ ] Alerts routed to a team that can act, with a documented first response per alert.
- [ ] On-call briefed that this system can take irreversible actions, and told how to stop it.
- [ ] Prompts, tool descriptions and policy thresholds are versioned, reviewed, and rolled out
      progressively — a prompt change is a production change.
- [ ] Model-version pinning; provider deprecation notices tracked; a tested plan for model upgrade
      (re-run the eval corpus, shadow-replay, then ramp).

---

## 7. Compliance & governance

- [ ] Data-processing agreement with the model provider covers the data classes in scope.
- [ ] Retention: prompts, outputs and audit records each have a defined retention and deletion path.
- [ ] Human-in-the-loop documented where regulation requires a human decision.
- [ ] Decision explainability: for any automated decision affecting a customer, you can produce the
      inputs, the rule, and the approver.
- [ ] Right-to-erasure path covers vector store embeddings and chat memory, not just the primary DB.
- [ ] Model and prompt changes recorded in a change log an auditor can read.

---

## 8. Testing

- [ ] Unit tests with a scripted `ChatModel` — deterministic, no network, run in CI.
- [ ] Contract tests for every tool (schema, validation, idempotency, dry-run).
- [ ] Gate tests: the ten cases in
      [04 §8](04-APPROVALS-AND-IRREVERSIBILITY.md#8-tests-you-must-have).
- [ ] Budget-exhaustion test per budget.
- [ ] Adversarial suite: injection via user input, retrieved documents, tool results, and MCP tool
      descriptions.
- [ ] Tenant-isolation test.
- [ ] Chaos: provider 500s, provider timeout, tool timeout, store unavailable, crash between intent
      and effect.
- [ ] Live eval suite on a schedule against the real model, with drift alerting.

---

## 9. Launch sequence

Do not go from zero to autonomous. Each stage answers a different question.

| Stage | Configuration | Question answered |
|-------|---------------|-------------------|
| 1. **Shadow** | `execution-mode=DRY_RUN`, runs on real traffic, no effects | does it decide correctly? |
| 2. **Human-approves-all** | every irreversible action gated, no auto-approve tier | is the model's judgement trustworthy? |
| 3. **Auto-approve the safe tier** | policy gate enabled for the lowest tier only | does the threshold hold in reality? |
| 4. **Widen the tier** | raise the threshold in steps, watching rejection rate | where is the real boundary? |
| 5. **Steady state** | tiered policy, dual control at the top | — |

Roll back a stage the moment the approval rejection rate rises. That metric is the whole feedback
loop.

---

## 10. Sign-off

| Area | Owner | Date | Status |
|------|-------|------|--------|
| Security review (boundaries, taint, credentials, egress) | | | ☐ |
| Irreversible-action catalogue reviewed by downstream owner | | | ☐ |
| Approval mechanics verified (all seven properties) | | | ☐ |
| Budgets and kill switch tested | | | ☐ |
| Observability, replay and evals in place | | | ☐ |
| Runbook published, on-call briefed | | | ☐ |
| Compliance and retention signed off | | | ☐ |
| Launch stage agreed (start at shadow) | | | ☐ |

---

**Back to:** [00 — Design Checklist](00-DESIGN-CHECKLIST.md)
