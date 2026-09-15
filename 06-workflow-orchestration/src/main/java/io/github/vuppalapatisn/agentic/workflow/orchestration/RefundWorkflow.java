package io.github.vuppalapatisn.agentic.workflow.orchestration;

import io.github.vuppalapatisn.agentic.workflow.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RefundRunResult;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RunStatus;
import io.github.vuppalapatisn.agentic.workflow.domain.RefundDecision;
import io.github.vuppalapatisn.agentic.workflow.effects.RefundEffects;
import io.github.vuppalapatisn.agentic.workflow.gate.ApprovalDesk;
import io.github.vuppalapatisn.agentic.workflow.gate.PolicyGate;
import io.github.vuppalapatisn.agentic.workflow.steps.CaseClassifier;
import io.github.vuppalapatisn.agentic.workflow.steps.FactGathering;
import io.github.vuppalapatisn.agentic.workflow.steps.ReplyDrafter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The workflow: a fixed directed graph, written as ordinary Java.
 *
 * <pre>
 *   loadOrder ──▶ gather(policy ∥ fraud) ──▶ classify (LLM) ──▶ gate ──┬─▶ pay ──▶ draft ──▶ notify
 *                                                                      ├─▶ suspend for approval
 *                                                                      └─▶ decline ──▶ draft ──▶ notify
 * </pre>
 *
 * <p>Four composition patterns appear here, and all four are decided by code:
 *
 * <ul>
 *   <li><b>chain</b> — the stage sequence in {@link #run};</li>
 *   <li><b>parallel fan-out/fan-in</b> — {@link FactGathering#gather};</li>
 *   <li><b>routing</b> — the {@code switch} on the gate decision;</li>
 *   <li><b>evaluator–optimiser</b> — {@link ReplyDrafter}.</li>
 * </ul>
 *
 * <p><b>Agency budget: 0.</b> One model call classifies, and up to four more draft and critique
 * prose. No model chooses a step, a branch, or a tool. The whole call sequence is assertable in a
 * unit test, which is the property an agent architecture trades away.
 *
 * <p>Every run records its executed steps, so the trace is a list of strings rather than a promise.
 */
@Service
public class RefundWorkflow {

    private static final Logger log = LoggerFactory.getLogger(RefundWorkflow.class);

    private final FactGathering factGathering;
    private final CaseClassifier classifier;
    private final PolicyGate policyGate;
    private final ApprovalDesk approvalDesk;
    private final ReplyDrafter replyDrafter;
    private final RefundEffects effects;
    private final Clock clock;

    public RefundWorkflow(FactGathering factGathering,
                          CaseClassifier classifier,
                          PolicyGate policyGate,
                          ApprovalDesk approvalDesk,
                          ReplyDrafter replyDrafter,
                          RefundEffects effects,
                          Clock clock) {
        this.factGathering = factGathering;
        this.classifier = classifier;
        this.policyGate = policyGate;
        this.approvalDesk = approvalDesk;
        this.replyDrafter = replyDrafter;
        this.effects = effects;
        this.clock = clock;
    }

    public Optional<RefundRunResult> run(String orderId, String customerMessage) {
        String runId = "r-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> steps = new ArrayList<>();
        long startedAt = System.nanoTime();

        OrderSummary order = factGathering.loadOrder(orderId).orElse(null);
        if (order == null) {
            return Optional.empty();
        }
        steps.add("loadOrder");

        try {
            CaseFacts facts = factGathering.gather(order);
            steps.add("gatherFacts(policy∥fraud)");

            RefundDecision decision = classifier.classify(runId, facts, customerMessage);
            steps.add("classify");

            PolicyGate.Decision gate = policyGate.decide(facts, decision);
            steps.add("gate:" + gate.rule());

            return Optional.of(switch (gate.outcome()) {
                case AUTO -> payAndNotify(runId, facts, decision, gate, steps, startedAt,
                        RunStatus.PAID, null);
                case NEEDS_APPROVAL -> suspend(runId, facts, decision, gate, steps, startedAt);
                case DENY -> declineAndNotify(runId, facts, decision, gate, steps, startedAt);
            });
        }
        catch (RuntimeException ex) {
            log.error("run {} failed at step {}: {}", runId, steps.size(), ex.toString());
            steps.add("failed:" + ex.getClass().getSimpleName());
            return Optional.of(new RefundRunResult(runId, RunStatus.FAILED,
                    RefundDecision.escalate(ex.getClass().getSimpleName()), "N/A",
                    order.totalMinor(), null, null,
                    "We could not complete this request automatically; a specialist will follow up.",
                    List.copyOf(steps), Duration.ofNanos(System.nanoTime() - startedAt), clock.instant()));
        }
    }

    /**
     * Resume after a human approval. In a workflow this is a <b>separate entry point</b>, not a
     * continuation: the original run already returned. The amount is re-read from the approval
     * record — nothing is re-classified, so the model gets no second turn.
     */
    public Optional<RefundRunResult> resumeApproved(String approvalId, String approver) {
        ApprovalDesk.PendingApproval approval = approvalDesk.approve(approvalId, approver).orElse(null);
        if (approval == null) {
            return Optional.empty();
        }
        if (!approval.fullyApproved()) {
            return Optional.of(new RefundRunResult(approval.runId(), RunStatus.AWAITING_APPROVAL,
                    null, approval.rule(), approval.amountMinor(), null, approval.id(),
                    "%d of %d approvals collected.".formatted(
                            approval.approvers().size(), approval.requiredApprovals()),
                    List.of("approve:" + approver), Duration.ZERO, clock.instant()));
        }

        ApprovalDesk.PendingApproval consumed = approvalDesk.consume(approvalId).orElse(null);
        if (consumed == null) {
            return Optional.empty();
        }

        OrderSummary order = factGathering.loadOrder(consumed.orderId()).orElse(null);
        if (order == null || order.totalMinor() != consumed.amountMinor()) {
            // The order changed after the approval was raised. Refuse rather than pay a stale one.
            return Optional.of(new RefundRunResult(consumed.runId(), RunStatus.FAILED, null,
                    consumed.rule(), consumed.amountMinor(), null, consumed.id(),
                    "The order changed after this approval was raised; nothing was paid.",
                    List.of("approve:" + approver, "staleApproval"), Duration.ZERO, clock.instant()));
        }

        List<String> steps = new ArrayList<>(List.of("approve:" + approver));
        RefundEffects.Receipt receipt = effects.issueRefund(consumed.runId(), order);
        steps.add("issueRefund");
        ReplyDrafter.DraftResult draft = replyDrafter.draft(order,
                new RefundDecision(RefundDecision.Outcome.REFUND, "APPROVED", order.totalMinor(),
                        io.github.vuppalapatisn.agentic.workflow.domain.Domain.RiskLevel.LOW,
                        "approved by " + approver));
        steps.add("draftReply(rounds=" + draft.rounds() + ")");
        effects.notifyCustomer(consumed.runId(), order, draft.reply());
        steps.add("notifyCustomer");

        return Optional.of(new RefundRunResult(consumed.runId(), RunStatus.APPROVED_AND_PAID, null,
                consumed.rule(), order.totalMinor(), receipt.receiptId(), consumed.id(),
                draft.reply(), List.copyOf(steps), Duration.ZERO, clock.instant()));
    }

    // ------------------------------------------------------------- branches

    private RefundRunResult payAndNotify(String runId, CaseFacts facts, RefundDecision decision,
                                         PolicyGate.Decision gate, List<String> steps, long startedAt,
                                         RunStatus status, String approvalId) {
        RefundEffects.Receipt receipt = effects.issueRefund(runId, facts.order());
        steps.add("issueRefund");
        ReplyDrafter.DraftResult draft = replyDrafter.draft(facts.order(), decision);
        steps.add("draftReply(rounds=" + draft.rounds() + ")");
        effects.notifyCustomer(runId, facts.order(), draft.reply());
        steps.add("notifyCustomer");
        return result(runId, status, decision, gate, facts, receipt.receiptId(), approvalId,
                draft.reply(), steps, startedAt);
    }

    private RefundRunResult suspend(String runId, CaseFacts facts, RefundDecision decision,
                                    PolicyGate.Decision gate, List<String> steps, long startedAt) {
        ApprovalDesk.PendingApproval approval = approvalDesk.request(
                runId, facts.order().orderId(), facts.order().totalMinor(), gate);
        steps.add("requestApproval");
        // No reply is drafted yet: there is nothing to tell the customer until a human decides.
        return result(runId, RunStatus.AWAITING_APPROVAL, decision, gate, facts, null, approval.id(),
                null, steps, startedAt);
    }

    private RefundRunResult declineAndNotify(String runId, CaseFacts facts, RefundDecision decision,
                                             PolicyGate.Decision gate, List<String> steps, long startedAt) {
        ReplyDrafter.DraftResult draft = replyDrafter.draft(facts.order(), decision);
        steps.add("draftReply(rounds=" + draft.rounds() + ")");
        effects.notifyCustomer(runId, facts.order(), draft.reply());
        steps.add("notifyCustomer");
        return result(runId, RunStatus.DECLINED, decision, gate, facts, null, null, draft.reply(),
                steps, startedAt);
    }

    private RefundRunResult result(String runId, RunStatus status, RefundDecision decision,
                                   PolicyGate.Decision gate, CaseFacts facts, String receiptId,
                                   String approvalId, String reply, List<String> steps, long startedAt) {
        return new RefundRunResult(runId, status, decision, gate.rule(), facts.order().totalMinor(),
                receiptId, approvalId, reply, List.copyOf(steps),
                Duration.ofNanos(System.nanoTime() - startedAt), clock.instant());
    }
}
