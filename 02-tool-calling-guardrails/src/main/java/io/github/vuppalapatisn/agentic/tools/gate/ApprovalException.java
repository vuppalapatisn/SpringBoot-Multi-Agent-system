package io.github.vuppalapatisn.agentic.tools.gate;

/**
 * Every way an approval can fail to authorise an effect. All of them fail <b>closed</b>.
 *
 * <p>The reason is a typed enum rather than a message so that metrics can be tagged with it:
 * a rise in {@link Reason#PAYLOAD_MISMATCH} is a security event, while a rise in
 * {@link Reason#EXPIRED} is an operational one.
 */
public class ApprovalException extends RuntimeException {

    public enum Reason {
        /** No token presented for an irreversible tool. */
        MISSING_TOKEN,
        /** Token does not correspond to any approval. */
        UNKNOWN_TOKEN,
        /** Approval exists but has not reached the required number of approvers. */
        NOT_APPROVED,
        /** The arguments at execution time differ from the ones that were approved. */
        PAYLOAD_MISMATCH,
        /** The TTL elapsed. Expiry means no. */
        EXPIRED,
        /** Already used — a second resume must not execute a second effect. */
        ALREADY_CONSUMED,
        /** Rejected by an approver. */
        REJECTED,
        /** Same approver tried to satisfy both halves of a dual-control requirement. */
        DUPLICATE_APPROVER
    }

    private final Reason reason;

    public ApprovalException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
