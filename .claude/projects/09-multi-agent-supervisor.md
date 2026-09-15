# Brief — 09 Multi-Agent Supervisor

**Purpose:** the value of multi-agent design is separation of authority, not extra intelligence.

**Read first:** `09-multi-agent-supervisor/docs/CFG.md`, then `authority/AgentRole`.

**Start in:** `supervisor/RefundSupervisor` (a deterministic switch) and
`authority/AgentCapabilities` (the startup check).

**The idea:** the agent that reads the attack has no power; the agent with the power has no prompt.

**Do not break**
- Only PAYOUT may hold an effectful capability; INTAKE holds nothing.
- `PayoutAgent` and `FraudAgent` have no `ChatClient`. Do not "upgrade" them.
- Handoffs are typed records with closed vocabularies. Another agent's output is R1.
- The raw customer message goes only to INTAKE (`rawMessageDoesNotCrossTheHandoff`).
- Intake flags are rules: the run stops before any specialist executes.
- UNCLEAR never pays; cited clauses are verified.
- The supervisor stays code. Making it a model changes the CFG and enables the ping-pong failure
  mode `RunLedger` is already checking for.

**Tests:** 26. `ScriptedChatModel` records prompts, which is how taint is asserted.
