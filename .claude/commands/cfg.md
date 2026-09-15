---
description: Draw (or refresh) the control-flow graph for a project before implementing agent code
argument-hint: "<project dir or feature description>"
---

Produce a control-flow graph for: **$ARGUMENTS**

Use the notation in `docs/01-CONTROL-FLOW-GRAPH.md` and the structure of
`docs/templates/CFG-TEMPLATE.md`. Write the result to `<project>/docs/CFG.md` when the target is an
existing project; otherwise output it for review first.

Work in this order — do not jump to the model nodes:

1. **Terminals** — every way the run can end.
2. **Ingress + authority** — trigger, and whose credentials the run uses.
3. **Effects** — every `{TOOL}`, each with a boundary class (`R0 R1 W1 W2 E1 E2 P1`); mark
   irreversible ones `⚠`.
4. **Gates** — a `<GATE>` on every inbound path to every `⚠`.
5. **Checkpoints** — a `[[STATE]]` before every `⚠`.
6. **Model nodes** — and for each one ask: does the model *decide*, or *classify* while code
   decides? Prefer classify.
7. **Cycle bounds** — numbers, not adjectives.
8. **Taint** — shade everything reachable from `R1`/MCP/retrieved content; flag any path that
   reaches an `⚠` argument without a deterministic validator.
9. **Compensation** — `⇠⇠` edges with windows; absence next to an `⚠` is the finding.

Then report:

- the **agency budget** (count of model-chosen `╌╌▶` edges), with a one-line justification for each
- the **irreversible-action catalogue** (the Phase 3 table)
- whether the **four graph invariants** hold, with evidence
- the recommended architecture (workflow / state machine / agent / multi-agent) and the specific
  runtime decision that justifies anything beyond a workflow

If the graph violates an invariant, say so before proposing code.
