# Architecture and Dataflow

Every project already has a **control-flow graph** in its own `docs/CFG.md` — what happens, in what
order, and where the gates are. This document covers the two views those do not:

* **Architecture** — what the components are, which layer they sit in, and what depends on what.
* **Dataflow** — what data moves where, its classification, and every trust boundary it crosses.

The dataflow diagrams are deliberately drawn in DFD style with explicit trust boundaries, because
that is the form a security review can actually read.

---

## Legend

| Notation | Meaning |
|----------|---------|
| `R0` `R1` `W1` `W2` `E1` `E2` `P1` | tool boundary class — see [03 — Tool Boundaries](03-TOOL-BOUNDARIES.md) |
| ⚠ | irreversible action |
| **C** / **I** / **R** | data classification: Confidential, Internal, Regulated |
| dashed edge | **model-chosen** — the agency budget counts these |
| dashed box | trust boundary; content inside is untrusted |
| 🛡 | a deterministic validator or gate |

---

## 1. System context

Nine services, one domain, and the external systems they touch. Numbers in brackets are ports.

```mermaid
flowchart TB
    subgraph clients["Callers"]
        OP["Support operator<br/>or upstream service"]
        APR["Approver<br/>refund-lead, finance"]
    end

    subgraph repo["This repository"]
        direction TB
        P01["01 foundation 8081<br/>classify only, no effects"]
        P02["02 guardrails 8082<br/>gated tool loop"]
        P03["03 rag 8083<br/>grounded answers"]
        P04["04 mcp-server 8084<br/>publishes tools"]
        P05["05 mcp-client 8085<br/>consumes tools"]
        P06["06 workflow 8086"]
        P07["07 state-machine 8087"]
        P08["08 agent-loop 8088"]
        P09["09 multi-agent 8089"]
    end

    subgraph ext["External systems"]
        LLM["Model provider<br/>Anthropic or Gemini"]
        FRAUD["Fraud provider<br/>partner API"]
        PAY["Payment provider<br/>money leaves here"]
        MAIL["Notification channel<br/>cannot be unsent"]
    end

    subgraph store["State"]
        H2[("H2<br/>runs, transitions,<br/>approvals, effects")]
        MEM[("In-memory<br/>everywhere else")]
    end

    OP --> P01 & P02 & P03 & P05 & P06 & P07 & P08 & P09
    APR --> P02 & P06 & P07
    APR --> P04

    P01 & P02 & P03 & P06 & P07 & P08 & P09 --> LLM
    P05 --> LLM
    P05 -->|"MCP streamable HTTP"| P04

    P02 & P06 & P07 & P08 & P09 --> FRAUD
    P04 --> FRAUD
    P02 & P06 & P07 & P08 & P09 --> PAY
    P04 --> PAY
    P02 & P06 & P07 & P08 & P09 --> MAIL

    P07 --> H2
    P02 & P06 & P08 & P09 --> MEM

    classDef irrev fill:#fde,stroke:#c07,stroke-width:2px
    class PAY,MAIL irrev
```

Two things to read off it:

* **Project 04 has no arrow to the model provider.** An MCP server is an ordinary service that
  publishes tools; that is exactly why it must enforce its own policy rather than trust its caller.
* **Only project 07 has a database.** That single difference is what makes its approvals durable,
  and it is the whole argument of [02 — Architecture Comparison](02-ARCHITECTURE-COMPARISON.md).

---

## 2. The layered shape every project shares

Different orchestration, same layering. The load-bearing rule is visible as an absence: **there is
no edge from the model to the effects layer.**

```mermaid
flowchart TB
    subgraph L1["Ingress — web/"]
        CTRL["@RestController<br/>@Valid request records<br/>🛡 pattern and size limits"]
    end

    subgraph L2["Orchestration — orchestration/ machine/ supervisor/ service/"]
        ORCH["RefundWorkflow · RefundStateMachine<br/>RefundAgent · RefundSupervisor"]
    end

    subgraph L3["Reasoning — steps/ agents/"]
        CLS["CaseClassifier · IntakeAgent · PolicyAgent<br/>structured output only"]
    end

    subgraph L4["Policy — gate/ budget/ authority/ boundary/"]
        GATE["🛡 PolicyGate · GuardedToolExecutor<br/>RunBudget · AgentCapabilities<br/>ApprovalStore"]
    end

    subgraph L5["Effects — effects/ tools/"]
        EFF["issueRefund ⚠ E2 · notifyCustomer ⚠ E2<br/>createRefundDraft W1"]
    end

    subgraph L6["Providers — provider/ store/"]
        PROV["OrderDirectory R0 · FraudService R1<br/>RefundLedger · RunRepository"]
    end

    MODEL(("Model<br/>provider"))

    CTRL --> ORCH
    ORCH --> CLS
    CLS -.->|"structured record"| ORCH
    ORCH --> GATE
    GATE -->|"authorised only"| EFF
    EFF --> PROV
    CLS <-->|"prompt / completion"| MODEL
    ORCH --> PROV

    GATE -.->|"refuse · suspend · decline"| ORCH

    linkStyle 4 stroke:#c07,stroke-width:3px
    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    classDef effect fill:#fde,stroke:#c07,stroke-width:2px
    classDef model fill:#eef,stroke:#66a,stroke-width:2px
    class GATE gate
    class EFF effect
    class MODEL,CLS model
```

| Rule | How the diagram shows it |
|------|--------------------------|
| The model classifies; code decides | `Reasoning` returns a record to `Orchestration`; it has no edge to `Policy` or `Effects` |
| No ungated one-way door | the only inbound edge to `Effects` comes from `Policy` |
| Gates are not advisors | `Policy` sits between orchestration and effects, not around the model call |
| Providers are reached directly for reads | `Orchestration → Providers` exists for `R0`/`R1` reads |

---

## 3. Component architecture

### 3.1 Project 02 — the guardrail core

Projects 06–09 change the orchestration and reuse this shape.

```mermaid
flowchart LR
    REQ["RefundController<br/>🛡 @Pattern @Size"] --> AGENT["RefundAgent<br/>ChatClient + tools"]
    AGENT <-.->|"agency = 4"| MODEL(("Model"))
    AGENT --> TOOLS["RefundTools<br/>@Tool + @ToolBoundary"]

    TOOLS --> REG["ToolRegistry<br/>🛡 unclassified tool<br/>= startup failure"]
    TOOLS --> PG["PolicyGate<br/>🛡 tiers: amount × risk × age"]

    PG -->|AUTO| GTE
    PG -->|NEEDS_APPROVAL| AS["ApprovalStore<br/>frozen payload + hash"]
    PG -->|DENY| DEC(["DECLINED"])

    AS --> APPROVER["ApprovalController<br/>human, 1 or 2 approvers"]
    APPROVER --> FER["FrozenEffectRunner<br/>🛡 no model turn after approval"]
    FER --> GTE

    GTE["GuardedToolExecutor<br/>🛡 mode · ceiling · token<br/>hash · idempotency"] --> IDEM[("IdempotencyLedger<br/>intent → outcome")]
    GTE --> LEDGER["RefundLedger ⚠ E2"]
    GTE --> NOTIF["NotificationGateway ⚠ E2<br/>🛡 recipient allowlist"]
    GTE --> AUDIT[("AuditLog<br/>append-only")]

    FS["FraudService<br/>R1 🛡 JSON → enum"] --> TOOLS
    OD["OrderDirectory R0<br/>authority for the amount"] --> TOOLS

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    classDef effect fill:#fde,stroke:#c07,stroke-width:2px
    class PG,GTE,REG,FER gate
    class LEDGER,NOTIF effect
```

### 3.2 Project 03 — retrieval with a verification gate

```mermaid
flowchart LR
    ASK["PolicyController<br/>🛡 tenant pattern"] --> SVC["PolicyAnswerService"]
    SVC -->|"filter from the PRINCIPAL<br/>never the question"| RET["VectorStoreDocumentRetriever<br/>topK 4 · threshold 0.25"]
    RET --> VS[("SimpleVectorStore")]
    EMB["HashingEmbeddingModel<br/>offline, deterministic"] --> VS
    ING["PolicyIngestion<br/>one clause = one document"] --> VS

    RET --> AUG["ContextualQueryAugmenter<br/>allowEmptyContext = false"]
    AUG --> MODEL(("Model"))
    MODEL --> GG["GroundednessGate 🛡<br/>≥1 citation · every citation retrieved<br/>context non-empty"]
    GG -->|pass| OK(["GROUNDED + citations"])
    GG -->|fail| NO(["REFUSED<br/>NO_CITATION · FABRICATED_CITATION<br/>NO_CONTEXT_RETRIEVED"])

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    class GG,AUG gate
```

The citation check resolves against **retrieval metadata**, not against the model's claim about what
it read. That is the whole trick.

### 3.3 Projects 04 + 05 — the MCP pair, two processes

```mermaid
flowchart LR
    subgraph client["05 mcp-client — trusts nothing"]
        CA["RemoteRefundAgent"] <-.-> CM(("Model"))
        CA --> FILTER["McpToolFilter → RemoteToolPolicy 🛡<br/>1 allowlist · 2 pinned fingerprint<br/>3 description scan · 4 our class wins"]
        FILTER --> ADM[("ToolAdmissionLog<br/>+ rug-pull alarm")]
    end

    subgraph server["04 mcp-server — trusts no caller, has no model"]
        MT["RefundMcpTools<br/>@McpTool + honest hints"] --> DESK["RefundDesk 🛡<br/>kill switch · rate limit · status rule<br/>idempotency · tiers"]
        DESK --> PAYS["payment ⚠ E2"]
        ADMIN["ApprovalAdminController<br/>NOT an MCP tool"] --> DESK
    end

    FILTER -->|"tools/call over streamable HTTP"| MT
    MT -.->|"result = R1 tainted data"| CA
    HUMAN["Approver"] --> ADMIN

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    classDef effect fill:#fde,stroke:#c07,stroke-width:2px
    class FILTER,DESK gate
    class PAYS effect
```

The division of labour: **the client controls exposure, the server controls execution.** Neither can
do the other's job, and approval is reachable from neither the model nor the MCP surface.

### 3.4 The four orchestrations, same job

**06 workflow** — a fixed DAG; `agency = 0`; the call sequence is assertable.

```mermaid
flowchart LR
    A["loadOrder R0"] --> B{{"fan-out"}}
    B --> C["lookupPolicy R0"] & D["checkFraud R1 🛡"]
    C & D --> E{{"fan-in → CaseFacts"}}
    E --> F(("classify")) --> G["🛡 PolicyGate"]
    G --> H["pay ⚠"] --> I["draft ↺ ≤2"] --> J["notify ⚠"]
```

**07 state machine** — states are rows; approvals durable; crash recovery designed.

```mermaid
flowchart LR
    A["CREATED"] --> B["FACTS_GATHERED"] --> C["CLASSIFIED"]
    C --> D["AWAITING_APPROVAL<br/>persisted + expiry"] --> E["PAYOUT_PENDING"]
    C --> E
    E --> F["PAID ⚠"] --> G["NOTIFIED ⚠"] --> H["CLOSED"]
    E -.->|"lost ack"| R["🛡 reconciler asks<br/>by idempotency key"] --> F
    F -.->|"notify fails"| K["COMPENSATING ⇠⇠"]
```

**08 agent loop** — the model picks the next tool; seven budgets bound it.

```mermaid
flowchart LR
    A(("model decides")) -.-> B["tools"]
    B --> C["🛡 RunBudget<br/>steps · calls · per-tool<br/>tokens · cost · clock · no-progress"]
    C --> A
    C -->|exhausted| X(["ESCALATED — fail closed"])
    B --> D["🛡 GuardedPayout"] --> E["pay ⚠"]
```

**09 multi-agent** — separation of authority; supervisor is code.

```mermaid
flowchart LR
    S{{"SUPERVISOR<br/>deterministic switch"}} --> I(("Intake<br/>authority: NONE"))
    I -->|"typed IntakeSummary"| P(("Policy<br/>R0"))
    I -->|"flag → stop"| X(["ESCALATED<br/>before any specialist"])
    P -->|"typed PolicyFinding"| F["Fraud<br/>R0+R1, no model"]
    F -->|"typed RiskFinding"| PO["Payout<br/>R0+W1+E2, NO MODEL"]
    PO --> G["🛡 GuardedPayout"] --> E["pay ⚠"]
```

---

## 4. Dataflow

### 4.1 Level 0 — context, with classification and trust boundaries

```mermaid
flowchart TB
    subgraph untrusted["UNTRUSTED ZONE"]
        CUST["Customer message<br/>C — attacker-controlled"]
        FRESP["Fraud provider response body<br/>I — partner-controlled"]
        MCPD["MCP tool descriptions + results<br/>I — third-party-controlled"]
        CORPUS["Retrieved policy clauses<br/>I — corpus-authored"]
    end

    subgraph trusted["TRUSTED ZONE — our records"]
        ORDER[("Order record<br/>C+PII<br/>AUTHORITY FOR THE AMOUNT")]
        POLICY[("Policy clauses<br/>I")]
        RUNS[("Run + transition log<br/>I")]
    end

    subgraph proc["PROCESSING"]
        V1["🛡 ingress validation"]
        V2["🛡 R1 → enum mapping"]
        V3["🛡 description scan + pin"]
        V4["🛡 citation set-membership"]
        MODEL(("Model — sees C and I"))
        GATE["🛡 policy gate<br/>reads TRUSTED values only"]
    end

    subgraph egress["EGRESS"]
        PAYO["Payment ⚠ R<br/>amount from ORDER"]
        MAILO["Notification ⚠ C<br/>template + allowlisted recipient"]
        RESP["API response<br/>C"]
    end

    CUST --> V1 --> MODEL
    FRESP --> V2 --> GATE
    MCPD --> V3 --> MODEL
    CORPUS --> MODEL
    MODEL --> V4 --> RESP
    MODEL -->|"typed record only"| GATE
    ORDER --> GATE
    POLICY --> MODEL
    GATE --> PAYO & MAILO
    GATE --> RUNS
    ORDER --> PAYO & MAILO

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    classDef effect fill:#fde,stroke:#c07,stroke-width:2px
    class V1,V2,V3,V4,GATE gate
    class PAYO,MAILO effect
```

**The single most important edge in this repository is the one that does not exist:** there is no
path from the untrusted zone into `PAYO`'s amount. The amount is read from `ORDER`.

### 4.2 Level 1 — the refund decision, boundary by boundary

Boundaries **B1–B5**: turning a request into a decision. Every green box is a deterministic
validator, and untrusted data is transformed at each one rather than carried forward as-is.

```mermaid
flowchart LR
    START(["POST /refunds/…<br/>orderId + message"]) --> B1["🛡 B1 ingress<br/>@Pattern A-1187<br/>@Size 8000"]

    B1 --> LOAD["read order — R0<br/>trusted facts<br/>incl. totalMinor"]
    B1 --> PROMPT
    LOAD --> FR["fraud provider — R1"] --> B2["🛡 B2 taint<br/>JSON → FraudSignal enum<br/>unknown ⇒ UNAVAILABLE<br/>which is not LOW"]

    PROMPT["build prompt<br/>trusted facts +<br/>FENCED message"] --> B3["🛡 B3 prompt<br/>untrusted text in the<br/>USER message only,<br/>never the system message"]
    LOAD --> PROMPT
    B3 --> LLM(("classify"))
    LLM --> B4["🛡 B4 reconcile<br/>clause ∈ supplied?<br/>amount = order total?<br/>mismatch ⇒ ESCALATE"]

    B4 --> GATE["🛡 B5 policy gate<br/>inputs: order record<br/>+ risk enum<br/>NOT the model's prose"]
    B2 --> GATE
    LOAD --> GATE

    GATE --> OUT{{"AUTO ·<br/>NEEDS_APPROVAL ·<br/>DENY"}}

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    class B1,B2,B3,B4,GATE gate
```

Boundaries **B6–B9**: turning a decision into an effect. Note that the approved path does **not**
re-enter the model, and that the intent is recorded before the money moves.

```mermaid
flowchart LR
    OUT{{"gate outcome"}}
    OUT -->|DENY| T1(["DECLINED"])
    OUT -->|AUTO| GUARD
    OUT -->|NEEDS_APPROVAL| FREEZE["🛡 B6 freeze<br/>payload hash<br/>+ amount + TTL"]

    FREEZE --> HUMAN["approver sees text<br/>rendered from<br/>TRUSTED data"]
    HUMAN -->|"approve"| B7["🛡 B7 consume<br/>hash match? expired?<br/>already used?<br/>distinct approvers?"]
    HUMAN -->|"decline or expire"| T3(["ESCALATED<br/>never auto-approved"])
    B7 --> GUARD

    GUARD["🛡 B8 guard<br/>kill switch · ceiling<br/>idempotency key from<br/>runId, never the model"]
    GUARD --> INTENT[("B9 intent<br/>recorded BEFORE<br/>the effect")]
    INTENT --> PAY["payment ⚠ E2<br/>same key sent<br/>downstream"]
    PAY --> OUTCOME[("outcome<br/>recorded")]
    OUTCOME --> NOTIF["notification ⚠ E2<br/>least reversible<br/>effect LAST"]
    NOTIF --> T2(["CLOSED"])

    classDef gate fill:#efe,stroke:#4a4,stroke-width:2px
    classDef effect fill:#fde,stroke:#c07,stroke-width:2px
    class FREEZE,B7,GUARD gate
    class PAY,NOTIF effect
```

| # | Boundary | Untrusted input | Validator | Test |
|---|----------|-----------------|-----------|------|
| B1 | ingress | HTTP body | Bean Validation, order-id pattern | `rejectsBadOrderId` |
| B2 | `R1` result | partner JSON | map to `FraudSignal`, unknown ⇒ `UNAVAILABLE` | `fraudProviderInjectionIsDroppedAtTheBoundary` |
| B3 | prompt | customer text | fenced user message, size-clipped | `untrustedTextNeverEntersTheSystemMessage` |
| B4 | model output | clause id, amount | must be supplied / must equal order total | `fabricatedClauseEscalates`, `amountMismatchEscalates` |
| B5 | decision | — | gate reads trusted values only | `PolicyGateTest` |
| B6 | approval | — | payload hash frozen | `refusesPayloadMismatch` |
| B7 | resume | approver identity | hash, expiry, single use, distinct approvers | `expiryNeverPays`, `dualControl` |
| B8 | effect | — | kill switch, ceiling, system-derived key | `killSwitchRefuses`, `idempotencyStopsASecondPayment` |
| B9 | checkpoint | — | intent recorded before the effect | `twoPhaseExecution` |

### 4.3 Where untrusted text can and cannot reach

```mermaid
flowchart LR
    T["Untrusted text<br/>customer message · partner JSON<br/>MCP description · corpus clause"]

    T -->|"CAN"| A["the model's context window"]
    T -->|"CAN"| B["prose shown to a human"]
    T -->|"CAN"| C["an escalation flag"]

    T -.->|"CANNOT"| D["the refund amount<br/>no amount parameter exists"]
    T -.->|"CANNOT"| E["the recipient<br/>read from the order"]
    T -.->|"CANNOT"| F["the gate decision<br/>reads trusted values only"]
    T -.->|"CANNOT"| G["the idempotency key<br/>derived from runId"]
    T -.->|"CANNOT"| H["a citation<br/>checked against retrieval metadata"]
    T -.->|"CANNOT"| I["a tool the allowlist excludes"]

    classDef no fill:#fee,stroke:#c33,stroke-width:2px,stroke-dasharray:4 3
    classDef yes fill:#eef,stroke:#66a
    class D,E,F,G,H,I no
    class A,B,C yes
```

Each "CANNOT" is structural rather than a prompt instruction — usually because the parameter simply
does not exist.

---

## 5. Key sequences

### 5.1 Automatic tier — nothing interrupts a human

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant O as Orchestrator
    participant D as OrderDirectory
    participant F as FraudService
    participant M as Model
    participant G as PolicyGate
    participant X as Guard
    participant P as Payment

    C->>O: orderId + message
    O->>D: read order (R0)
    D-->>O: totalMinor = 8990
    O->>F: risk for customer (R1)
    F-->>O: CLEAN (enum only)
    O->>M: trusted facts + fenced message
    M-->>O: RefundDecision record
    O->>O: reconcile clause + amount
    O->>G: decide(facts, decision)
    G-->>O: AUTO, rule AUTO_LOW_VALUE_LOW_RISK
    O->>X: execute(effect ctx)
    X->>X: key = sha256(runId|tool|order|amount)
    X->>X: record INTENT
    X->>P: pay(order, amount, key)
    P-->>X: receipt
    X->>X: record APPLIED
    X-->>O: receipt
    O-->>C: PAID + gateRule + steps
```

### 5.2 Human approval — and the attack the frozen hash defeats

```mermaid
sequenceDiagram
    autonumber
    participant M as Model
    participant G as PolicyGate
    participant S as ApprovalStore
    participant H as Approver
    participant R as FrozenEffectRunner
    participant X as Guard

    M->>G: issueRefund(A-1187)
    G-->>S: NEEDS_APPROVAL, 1 approver
    S->>S: freeze payload, hash 9f2c…, TTL 24h
    S-->>M: APPROVAL_REQUIRED + id (nothing paid)
    Note over M: the model's turn ends here
    H->>S: approve(ap-1a2b, u-114)
    S->>R: approved payload (NOT a new model call)
    R->>X: execute(frozen ctx, token)
    X->>S: consume(token, hash of ACTUAL args)
    alt hash matches
        S-->>X: authorised, single use
        X-->>R: paid once
    else hash differs
        S-->>X: PAYLOAD_MISMATCH
        Note over X: approver saw 240.00 — this would have paid 2400.00
    end
```

### 5.3 MCP tool admission — before the model sees anything

```mermaid
sequenceDiagram
    autonumber
    participant S as 04 mcp-server
    participant F as McpToolFilter
    participant P as RemoteToolPolicy
    participant L as AdmissionLog
    participant M as Model

    S-->>F: tools/list
    loop per tool
        F->>P: evaluate(server, tool)
        P->>P: 1 on the allowlist for THIS server?
        P->>P: 2 description free of instruction shapes?
        P->>P: 3 fingerprint matches the pin?
        P->>P: 4 hints consistent with OUR class?
        P-->>F: ALLOWED | UNPINNED | withheld + reason
        F->>L: record
    end
    F-->>M: only admitted tools
    Note over M: a withheld tool's description<br/>never enters the context window
```

### 5.4 Budget exhaustion — fail closed

```mermaid
sequenceDiagram
    autonumber
    participant M as Model
    participant A as BudgetAdvisor
    participant B as RunBudget
    participant T as Tools
    participant S as Service

    loop until done or a budget trips
        A->>B: beginStep()
        M->>T: call lookupOrder(A-1204)
        T->>B: beforeToolCall(name, signature)
        B-->>T: ok
        A->>B: recordUsage(tokens)
    end
    M->>T: call lookupOrder(A-1204) again
    T->>B: beforeToolCall(same signature)
    B--xT: BudgetExceededException(NO_PROGRESS)
    Note over T: rethrown, not turned into<br/>a message the model can ignore
    T--xS: propagates
    S-->>M: ESCALATED, nothing paid
```

---

## 6. Deployment

```mermaid
flowchart TB
    subgraph host["Docker host — Docker Desktop on macOS, linux/arm64 native"]
        subgraph net["compose network: agentic-reference"]
            C01["01 :8081"]
            C02["02 :8082"]
            C03["03 :8083"]
            C04["04 :8084"]
            C05["05 :8085"]
            C06["06 :8086"]
            C07["07 :8087"]
            C08["08 :8088"]
            C09["09 :8089"]
            VOL[("volume<br/>state-machine-data<br/>/app/data")]
        end
        ENVF["env_file .env<br/>AI_CHAT_PROVIDER<br/>ANTHROPIC_API_KEY or GEMINI_API_KEY"]
    end

    PROV(("Model provider<br/>Anthropic or Gemini"))

    C05 -->|"http://04-mcp-server:8084/mcp<br/>service name, not localhost"| C04
    C07 --- VOL
    ENVF -.-> C01 & C02 & C03 & C04 & C05 & C06 & C07 & C08 & C09
    C01 & C02 & C03 & C05 & C06 & C07 & C08 & C09 --> PROV

    classDef nokey fill:#efe,stroke:#4a4
    class C04 nokey
```

Each image is multi-stage with layered-jar extraction, runs as non-root, and carries a
`HEALTHCHECK` on `/actuator/health`. Project 04 needs no API key of any kind. Full walkthrough:
[RUNNING-WITH-DOCKER.md](RUNNING-WITH-DOCKER.md).

---

## 7. Keeping these current

These diagrams describe structure. When a change alters it:

| Change | Update |
|--------|--------|
| a new component or layer | §2 / §3 |
| a new tool, or a tool's boundary class | §3, §4.1, and the project's `docs/CFG.md` |
| a new trust boundary or validator | §4.1, §4.2 **and its table row** |
| a new external system | §1 |
| a new gate or budget | §3, §5 |
| anything about ports or containers | §6 and `docker-compose.yml` |

The per-project `docs/CFG.md` remains the authority on **control flow and the four graph
invariants**; this document is the structural companion to it.
