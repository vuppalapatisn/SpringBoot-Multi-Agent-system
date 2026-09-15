package io.github.vuppalapatisn.agentic.tools.domain;

/**
 * What a refund tool returns — to the model and to a direct caller alike.
 *
 * <p>Note that a blocked effect is a <b>value</b>, not an exception, from the model's point of view.
 * The model is told "an approval is required, here is its id" and can carry on doing something
 * useful. The guard still refused; the model simply learns that it cannot proceed rather than
 * seeing a stack trace it might try to work around.
 */
public record RefundActionResult(
        ActionStatus status,
        String message,
        String approvalId,
        String payloadHash,
        RefundReceipt receipt) {

    public enum ActionStatus {
        /** The effect happened. */
        APPLIED,
        /** An identical effect had already happened; the prior receipt is returned. */
        REPLAYED,
        /** Nothing happened: dry-run mode. The receipt is synthetic. */
        DRY_RUN,
        /** Suspended pending human approval. Nothing happened. */
        APPROVAL_REQUIRED,
        /** A policy rule forbids it. Nothing happened, and this is not an escalation. */
        DECLINED,
        /** The guard refused: no token, hash mismatch, expired, disabled, or ceiling exceeded. */
        REFUSED
    }

    public static RefundActionResult applied(RefundReceipt receipt, String message) {
        return new RefundActionResult(ActionStatus.APPLIED, message, null, null, receipt);
    }

    public static RefundActionResult dryRun(RefundReceipt receipt) {
        return new RefundActionResult(ActionStatus.DRY_RUN,
                "Dry run: nothing was changed.", null, null, receipt);
    }

    public static RefundActionResult approvalRequired(String approvalId, String payloadHash, String message) {
        return new RefundActionResult(ActionStatus.APPROVAL_REQUIRED, message, approvalId, payloadHash, null);
    }

    public static RefundActionResult declined(String message) {
        return new RefundActionResult(ActionStatus.DECLINED, message, null, null, null);
    }

    public static RefundActionResult refused(String message) {
        return new RefundActionResult(ActionStatus.REFUSED, message, null, null, null);
    }
}
