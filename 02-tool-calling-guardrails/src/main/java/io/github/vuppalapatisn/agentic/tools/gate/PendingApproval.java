package io.github.vuppalapatisn.agentic.tools.gate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A suspended effect awaiting human approval — the durable, frozen record of an intent.
 *
 * <p>Seven properties of a real approval (see {@code docs/04-APPROVALS-AND-IRREVERSIBILITY.md}) and
 * where each one lives:
 *
 * <table>
 *   <tr><td>durable</td><td>this record is stored before the tool returns to the model</td></tr>
 *   <tr><td>frozen</td><td>{@link #payload} + {@link #payloadHash}</td></tr>
 *   <tr><td>attributed</td><td>{@link #approvals}</td></tr>
 *   <tr><td>bounded</td><td>{@link #expiresAt} — expiry means <b>no</b></td></tr>
 *   <tr><td>replay-safe</td><td>single-use consumption + the idempotency ledger</td></tr>
 *   <tr><td>legible</td><td>{@link #humanExplanation}, rendered by the policy gate</td></tr>
 *   <tr><td>refusable</td><td>{@link ApprovalStatus#REJECTED} is terminal</td></tr>
 * </table>
 */
public record PendingApproval(
        String id,
        String runId,
        String toolName,
        String businessKey,
        long amountMinor,
        Map<String, Object> payload,
        String payloadHash,
        String rule,
        String humanExplanation,
        int requiredApprovals,
        List<Approval> approvals,
        ApprovalStatus status,
        Instant createdAt,
        Instant expiresAt) {

    /** One approver's decision. Immutable, and part of the audit record. */
    public record Approval(String approver, Instant at) {
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean fullyApproved() {
        return approvals.size() >= requiredApprovals;
    }

    PendingApproval withStatus(ApprovalStatus newStatus) {
        return new PendingApproval(id, runId, toolName, businessKey, amountMinor, payload, payloadHash,
                rule, humanExplanation, requiredApprovals, approvals, newStatus, createdAt, expiresAt);
    }

    PendingApproval withApproval(Approval approval) {
        List<Approval> merged = java.util.stream.Stream
                .concat(approvals.stream(), java.util.stream.Stream.of(approval)).toList();
        ApprovalStatus newStatus = merged.size() >= requiredApprovals
                ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING;
        return new PendingApproval(id, runId, toolName, businessKey, amountMinor, payload, payloadHash,
                rule, humanExplanation, requiredApprovals, merged, newStatus, createdAt, expiresAt);
    }
}
