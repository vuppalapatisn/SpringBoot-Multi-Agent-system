package io.github.vuppalapatisn.agentic.workflow.gate;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending approvals for a workflow that has already returned.
 *
 * <p><b>This is where the workflow architecture shows its limit.</b> A workflow is a function call:
 * it runs to completion and returns. It has nowhere to <i>be</i> while a human takes a day to
 * decide, so the pending decision has to be parked in a side store and picked up by a second,
 * unrelated request — which means the run has no state, no resumable position, and no audit of what
 * happened between the two halves.
 *
 * <p>It works. It is also the moment to ask whether you have outgrown a workflow: if approvals are
 * routine rather than exceptional, the honest answer is a state machine
 * ({@link io.github.vuppalapatisn.agentic.workflow.orchestration.RefundWorkflow} → project 07),
 * where {@code AWAITING_APPROVAL} is a persisted state rather than an absence.
 */
@Component
public class ApprovalDesk {

    public record PendingApproval(
            String id,
            String runId,
            String orderId,
            long amountMinor,
            String rule,
            String humanExplanation,
            int requiredApprovals,
            List<String> approvers,
            boolean executed,
            Instant createdAt) {

        public boolean fullyApproved() {
            return approvers.size() >= requiredApprovals;
        }
    }

    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final Clock clock;

    public ApprovalDesk(Clock clock) {
        this.clock = clock;
    }

    public PendingApproval request(String runId, String orderId, long amountMinor,
                                   PolicyGate.Decision decision) {
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8),
                runId, orderId, amountMinor, decision.rule(), decision.humanExplanation(),
                Math.max(1, decision.requiredApprovals()), List.of(), false, clock.instant());
        approvals.put(approval.id(), approval);
        return approval;
    }

    /**
     * Records one approver. Rejects a repeat approver so dual control means two people.
     *
     * @return the updated approval, or empty if the id is unknown
     */
    public Optional<PendingApproval> approve(String id, String approver) {
        PendingApproval approval = approvals.get(id);
        if (approval == null || approval.executed() || approval.approvers().contains(approver)) {
            return Optional.empty();
        }
        List<String> approvers = java.util.stream.Stream
                .concat(approval.approvers().stream(), java.util.stream.Stream.of(approver)).toList();
        PendingApproval updated = new PendingApproval(approval.id(), approval.runId(),
                approval.orderId(), approval.amountMinor(), approval.rule(),
                approval.humanExplanation(), approval.requiredApprovals(), approvers, false,
                approval.createdAt());
        approvals.put(id, updated);
        return Optional.of(updated);
    }

    /** Marks an approval used. Single-use, so a duplicated resume cannot pay twice. */
    public Optional<PendingApproval> consume(String id) {
        PendingApproval approval = approvals.get(id);
        if (approval == null || approval.executed() || !approval.fullyApproved()) {
            return Optional.empty();
        }
        PendingApproval used = new PendingApproval(approval.id(), approval.runId(),
                approval.orderId(), approval.amountMinor(), approval.rule(),
                approval.humanExplanation(), approval.requiredApprovals(), approval.approvers(),
                true, approval.createdAt());
        approvals.put(id, used);
        return Optional.of(used);
    }

    public Optional<PendingApproval> find(String id) {
        return Optional.ofNullable(approvals.get(id));
    }

    public List<PendingApproval> pending() {
        return approvals.values().stream().filter(approval -> !approval.executed()).toList();
    }
}
