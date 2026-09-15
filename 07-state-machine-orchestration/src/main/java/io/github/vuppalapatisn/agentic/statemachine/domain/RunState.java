package io.github.vuppalapatisn.agentic.statemachine.domain;

import java.util.Set;

/**
 * The machine's states, and the transitions allowed out of each one.
 *
 * <p><b>This enum is the design document.</b> Everything a reviewer needs in order to ask "what
 * happens if the pod restarts here?" is visible in one place, which is the main reason to choose a
 * state machine over a workflow: the positions a run can occupy are enumerated rather than implied
 * by a call stack.
 *
 * <p>Note {@link #PAYOUT_PENDING}. It exists solely so that a crash between recording the intent to
 * pay and learning the outcome leaves the run in a state a sweeper can reconcile — the durable
 * expression of "no effect before checkpoint".
 */
public enum RunState {

    /** The run exists; nothing has been read yet. */
    CREATED,

    /** Order, policy and fraud data gathered. */
    FACTS_GATHERED,

    /** The model has classified the request and the output has been reconciled. */
    CLASSIFIED,

    /** A human must decide. <b>Durable</b>: this survives a restart, and it can expire. */
    AWAITING_APPROVAL,

    /** Intent to pay is recorded. The effect may or may not have reached the provider. */
    PAYOUT_PENDING,

    /** Money moved. The compensation window is open until {@code settles_at}. */
    PAID,

    /** The customer has been told. This effect has no compensation. */
    NOTIFIED,

    /** Unwinding a partially-completed run. */
    COMPENSATING,

    // ---- terminal ----

    /** Happy path complete. */
    CLOSED,

    /** Refused by rule. */
    DECLINED,

    /** An approver said no. */
    REJECTED,

    /** Nobody answered in time. Expiry means no. */
    EXPIRED,

    /** Unrecoverable, and compensated where possible. */
    FAILED,

    /**
     * Compensation was required but is no longer possible — the window closed. A human must act.
     * Deliberately distinct from {@link #FAILED}: this one pages someone.
     */
    NEEDS_MANUAL_INTERVENTION;

    private static final Set<RunState> TERMINAL =
            Set.of(CLOSED, DECLINED, REJECTED, EXPIRED, FAILED, NEEDS_MANUAL_INTERVENTION);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /** The transition table. A transition not listed here is rejected by the machine. */
    public Set<RunState> allowedNext() {
        return switch (this) {
            case CREATED -> Set.of(FACTS_GATHERED, FAILED);
            case FACTS_GATHERED -> Set.of(CLASSIFIED, FAILED);
            case CLASSIFIED -> Set.of(AWAITING_APPROVAL, PAYOUT_PENDING, DECLINED, FAILED);
            case AWAITING_APPROVAL -> Set.of(PAYOUT_PENDING, REJECTED, EXPIRED, FAILED);
            case PAYOUT_PENDING -> Set.of(PAID, COMPENSATING, FAILED);
            case PAID -> Set.of(NOTIFIED, COMPENSATING, FAILED);
            case NOTIFIED -> Set.of(CLOSED);
            case COMPENSATING -> Set.of(FAILED, NEEDS_MANUAL_INTERVENTION);
            case CLOSED, DECLINED, REJECTED, EXPIRED, FAILED, NEEDS_MANUAL_INTERVENTION -> Set.of();
        };
    }

    public boolean canMoveTo(RunState next) {
        return allowedNext().contains(next);
    }
}
