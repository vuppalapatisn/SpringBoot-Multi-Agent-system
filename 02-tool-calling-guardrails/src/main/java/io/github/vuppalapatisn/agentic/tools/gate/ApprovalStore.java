package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.audit.AuditLog;
import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds suspended effects and the approvals against them.
 *
 * <p>In memory here so the project runs with no infrastructure — which also means an approval does
 * not survive a restart. That is exactly the limitation that motivates project 07, where the
 * approval is a persisted state instead. The <i>semantics</i> below are the production ones:
 * frozen payload, hash check, expiry that means no, single-use consumption, no duplicate approver.
 */
@Component
public class ApprovalStore {

    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final GuardrailProperties properties;
    private final AuditLog auditLog;
    private final Clock clock;

    public ApprovalStore(GuardrailProperties properties, AuditLog auditLog, Clock clock) {
        this.properties = properties;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Suspend an effect for human approval. The payload is frozen at this moment. */
    public PendingApproval request(EffectContext context, GateDecision decision) {
        Instant now = clock.instant();
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8),
                context.runId(),
                context.toolName(),
                context.businessKey(),
                context.amountMinor(),
                Map.copyOf(context.payload()),
                PayloadHash.of(context.payload()),
                decision.rule(),
                decision.humanExplanation(),
                Math.max(1, decision.requiredApprovals()),
                List.of(),
                ApprovalStatus.PENDING,
                now,
                now.plus(properties.approvalTtl()));
        approvals.put(approval.id(), approval);
        auditLog.gate(context.runId(), context.toolName(), decision.rule(),
                "APPROVAL_REQUESTED", approval.payloadHash(), approval.id());
        return approval;
    }

    /**
     * Record an automatic approval by a policy rule. Attribution is {@code policy:<rule>} rather
     * than a person, so the audit trail never implies a human decided something a rule did.
     */
    public PendingApproval autoApprove(EffectContext context, GateDecision decision) {
        Instant now = clock.instant();
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8),
                context.runId(), context.toolName(), context.businessKey(), context.amountMinor(),
                Map.copyOf(context.payload()), PayloadHash.of(context.payload()),
                decision.rule(), decision.humanExplanation(),
                1,
                List.of(new PendingApproval.Approval("policy:" + decision.rule(), now)),
                ApprovalStatus.APPROVED,
                now,
                now.plus(properties.approvalTtl()));
        approvals.put(approval.id(), approval);
        auditLog.gate(context.runId(), context.toolName(), decision.rule(),
                "AUTO_APPROVED", approval.payloadHash(), approval.id());
        return approval;
    }

    public PendingApproval approve(String id, String approver) {
        PendingApproval approval = require(id);
        assertActionable(approval);
        if (approval.approvals().stream().anyMatch(a -> a.approver().equals(approver))) {
            throw new ApprovalException(ApprovalException.Reason.DUPLICATE_APPROVER,
                    approver + " has already approved " + id
                            + "; dual control needs two distinct approvers");
        }
        PendingApproval updated = approval.withApproval(
                new PendingApproval.Approval(approver, clock.instant()));
        approvals.put(id, updated);
        auditLog.gate(approval.runId(), approval.toolName(), approval.rule(),
                updated.status() == ApprovalStatus.APPROVED ? "APPROVED" : "PARTIALLY_APPROVED",
                approval.payloadHash(), approver);
        return updated;
    }

    public PendingApproval reject(String id, String approver) {
        PendingApproval approval = require(id);
        assertActionable(approval);
        PendingApproval updated = approval
                .withApproval(new PendingApproval.Approval(approver, clock.instant()))
                .withStatus(ApprovalStatus.REJECTED);
        approvals.put(id, updated);
        auditLog.gate(approval.runId(), approval.toolName(), approval.rule(),
                "REJECTED", approval.payloadHash(), approver);
        return updated;
    }

    /**
     * Authorise one execution. Single-use: a second call with the same token fails, which is what
     * makes a duplicated resume safe even before the idempotency ledger is consulted.
     *
     * @param token       the approval id
     * @param payloadHash hash of the arguments about to be executed
     */
    public PendingApproval consume(String token, String payloadHash) {
        if (token == null || token.isBlank()) {
            throw new ApprovalException(ApprovalException.Reason.MISSING_TOKEN,
                    "an irreversible tool was called without an approval token");
        }
        PendingApproval approval = approvals.get(token);
        if (approval == null) {
            throw new ApprovalException(ApprovalException.Reason.UNKNOWN_TOKEN,
                    "no approval found for token " + token);
        }
        if (approval.status() == ApprovalStatus.EXECUTED) {
            throw new ApprovalException(ApprovalException.Reason.ALREADY_CONSUMED,
                    "approval " + token + " has already authorised an execution");
        }
        if (approval.status() == ApprovalStatus.REJECTED) {
            throw new ApprovalException(ApprovalException.Reason.REJECTED,
                    "approval " + token + " was rejected");
        }
        if (approval.expired(clock.instant())) {
            approvals.put(token, approval.withStatus(ApprovalStatus.EXPIRED));
            throw new ApprovalException(ApprovalException.Reason.EXPIRED,
                    "approval " + token + " expired at " + approval.expiresAt());
        }
        if (approval.status() != ApprovalStatus.APPROVED || !approval.fullyApproved()) {
            throw new ApprovalException(ApprovalException.Reason.NOT_APPROVED,
                    "approval " + token + " has " + approval.approvals().size() + " of "
                            + approval.requiredApprovals() + " required approvals");
        }
        if (!approval.payloadHash().equals(payloadHash)) {
            throw new ApprovalException(ApprovalException.Reason.PAYLOAD_MISMATCH,
                    "the arguments being executed are not the ones that were approved for " + token);
        }
        approvals.put(token, approval.withStatus(ApprovalStatus.EXECUTED));
        return approval;
    }

    /** Phase 8: a sweeper, not a hope. Expiry is a state transition, so it is auditable. */
    @Scheduled(fixedDelayString = "PT1M")
    public void sweepExpired() {
        Instant now = clock.instant();
        approvals.values().stream()
                .filter(approval -> approval.status() == ApprovalStatus.PENDING && approval.expired(now))
                .forEach(approval -> {
                    approvals.put(approval.id(), approval.withStatus(ApprovalStatus.EXPIRED));
                    auditLog.gate(approval.runId(), approval.toolName(), approval.rule(),
                            "EXPIRED", approval.payloadHash(), "sweeper");
                });
    }

    public Optional<PendingApproval> find(String id) {
        return Optional.ofNullable(approvals.get(id));
    }

    public List<PendingApproval> pending() {
        return approvals.values().stream()
                .filter(approval -> approval.status() == ApprovalStatus.PENDING)
                .sorted(Comparator.comparing(PendingApproval::createdAt))
                .toList();
    }

    private PendingApproval require(String id) {
        PendingApproval approval = approvals.get(id);
        if (approval == null) {
            throw new ApprovalException(ApprovalException.Reason.UNKNOWN_TOKEN, "no approval " + id);
        }
        return approval;
    }

    private void assertActionable(PendingApproval approval) {
        if (approval.expired(clock.instant())) {
            approvals.put(approval.id(), approval.withStatus(ApprovalStatus.EXPIRED));
            throw new ApprovalException(ApprovalException.Reason.EXPIRED,
                    "approval " + approval.id() + " expired at " + approval.expiresAt());
        }
        if (approval.status() == ApprovalStatus.REJECTED) {
            throw new ApprovalException(ApprovalException.Reason.REJECTED,
                    "approval " + approval.id() + " was already rejected");
        }
        if (approval.status() == ApprovalStatus.EXECUTED) {
            throw new ApprovalException(ApprovalException.Reason.ALREADY_CONSUMED,
                    "approval " + approval.id() + " has already been executed");
        }
    }
}
