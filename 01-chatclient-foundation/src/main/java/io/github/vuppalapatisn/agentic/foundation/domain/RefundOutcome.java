package io.github.vuppalapatisn.agentic.foundation.domain;

/** Closed set of classifications. A closed set is what makes the downstream gate writable. */
public enum RefundOutcome {
    REFUND,
    DECLINE,
    ESCALATE
}
