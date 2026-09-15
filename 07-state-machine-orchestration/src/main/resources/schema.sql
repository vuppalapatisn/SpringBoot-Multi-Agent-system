-- Project 07 — the state machine's durable storage.
--
-- Four tables, and the shape of each one is a design decision:
--
--  refund_run       one row per run; `state` is the machine's position and is only ever changed
--                   by a guarded transition (UPDATE ... WHERE state = expected)
--  run_transition   append-only audit of every state change, with the actor and reason
--  refund_effect    the idempotency ledger: intent recorded before the effect, outcome after
--  run_approval     the human gate as data: frozen amount, payload hash, approvers, expiry
--
-- In production these carry no UPDATE/DELETE grant for run_transition, and refund_effect has a
-- unique index on idempotency_key. H2 here so the project runs with no infrastructure.

CREATE TABLE IF NOT EXISTS refund_run (
    run_id            VARCHAR(40)  PRIMARY KEY,
    order_id          VARCHAR(20)  NOT NULL,
    customer_id       VARCHAR(40)  NOT NULL,
    customer_email    VARCHAR(200) NOT NULL,
    amount_minor      BIGINT       NOT NULL,
    currency          VARCHAR(8)   NOT NULL,
    state             VARCHAR(40)  NOT NULL,
    gate_rule         VARCHAR(60),
    decision_outcome  VARCHAR(20),
    decision_clause   VARCHAR(60),
    decision_risk     VARCHAR(20),
    fraud_signal      VARCHAR(30),
    customer_message  CLOB,
    customer_reply    CLOB,
    receipt_id        VARCHAR(60),
    idempotency_key   VARCHAR(80),
    settles_at        TIMESTAMP,
    failure_reason    VARCHAR(400),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_run_state ON refund_run (state);

CREATE TABLE IF NOT EXISTS run_transition (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      VARCHAR(40)  NOT NULL,
    seq         INT          NOT NULL,
    from_state  VARCHAR(40)  NOT NULL,
    to_state    VARCHAR(40)  NOT NULL,
    actor       VARCHAR(60)  NOT NULL,
    reason      VARCHAR(400),
    at          TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_transition_run ON run_transition (run_id, seq);

CREATE TABLE IF NOT EXISTS refund_effect (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    run_id          VARCHAR(40) NOT NULL,
    effect          VARCHAR(40) NOT NULL,
    business_key    VARCHAR(40) NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    phase           VARCHAR(20) NOT NULL,
    result_ref      VARCHAR(80),
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_effect_phase ON refund_effect (phase);

CREATE TABLE IF NOT EXISTS run_approval (
    approval_id        VARCHAR(40) PRIMARY KEY,
    run_id             VARCHAR(40) NOT NULL,
    order_id           VARCHAR(20) NOT NULL,
    amount_minor       BIGINT      NOT NULL,
    payload_hash       VARCHAR(80) NOT NULL,
    rule               VARCHAR(60) NOT NULL,
    explanation        CLOB        NOT NULL,
    required_approvals INT         NOT NULL,
    approvers          VARCHAR(400) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    created_at         TIMESTAMP   NOT NULL,
    expires_at         TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_run_approval_status ON run_approval (status, expires_at);
