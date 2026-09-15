package io.github.vuppalapatisn.agentic.tools.gate;

/**
 * Lifecycle of an approval. {@link #EXPIRED} is a terminal state that means <b>not approved</b>:
 * the unavailability of a human must never become a yes.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    EXECUTED
}
