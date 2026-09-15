package io.github.vuppalapatisn.agentic.statemachine.machine;

import io.github.vuppalapatisn.agentic.statemachine.config.StateMachineProperties;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.domain.RefundDecision;
import io.github.vuppalapatisn.agentic.statemachine.domain.RunState;
import io.github.vuppalapatisn.agentic.statemachine.effects.RefundProvider;
import io.github.vuppalapatisn.agentic.statemachine.gate.PolicyGate;
import io.github.vuppalapatisn.agentic.statemachine.steps.CaseClassifier;
import io.github.vuppalapatisn.agentic.statemachine.steps.FactGathering;
import io.github.vuppalapatisn.agentic.statemachine.store.ApprovalRepository;
import io.github.vuppalapatisn.agentic.statemachine.store.EffectLedger;
import io.github.vuppalapatisn.agentic.statemachine.store.RunRepository;
import io.github.vuppalapatisn.agentic.statemachine.support.Hashing;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Refund Desk as an explicit, persisted state machine.
 *
 * <pre>
 *  CREATED ─▶ FACTS_GATHERED ─▶ CLASSIFIED ─┬─▶ DECLINED
 *                                           ├─▶ PAYOUT_PENDING ─▶ PAID ─▶ NOTIFIED ─▶ CLOSED
 *                                           └─▶ AWAITING_APPROVAL ─┬─(approve)─▶ PAYOUT_PENDING
 *                                                                  ├─(reject)──▶ REJECTED
 *                                                                  └─(timeout)─▶ EXPIRED
 *                     PAYOUT_PENDING ─(provider error)─▶ COMPENSATING ─▶ FAILED
 *                     PAID ─(notify fails)─▶ COMPENSATING ─┬─(in window)──▶ FAILED
 *                                                          └─(window closed)─▶ NEEDS_MANUAL_INTERVENTION
 * </pre>
 *
 * <p>Three properties this architecture has and a workflow does not:
 *
 * <ol>
 *   <li><b>The run has somewhere to be.</b> {@code AWAITING_APPROVAL} is a row, so it survives a
 *       restart and can expire on a timer.</li>
 *   <li><b>Every move is audited.</b> {@code run_transition} records from, to, actor and reason, so
 *       "what state was this run in at 14:02, and who moved it?" is answerable.</li>
 *   <li><b>Crash recovery is designed, not hoped for.</b> {@code PAYOUT_PENDING} exists precisely
 *       so a crash between intent and outcome leaves a state a sweeper can reconcile.</li>
 * </ol>
 *
 * <p>{@link #advance} is safe to call repeatedly: every move goes through a guarded transition, so
 * a duplicate call finds the run already moved and does nothing.
 */
@Service
public class RefundStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RefundStateMachine.class);

    private final RunRepository runs;
    private final ApprovalRepository approvals;
    private final EffectLedger ledger;
    private final FactGathering factGathering;
    private final CaseClassifier classifier;
    private final PolicyGate policyGate;
    private final RefundProvider provider;
    private final StateMachineProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public RefundStateMachine(RunRepository runs,
                              ApprovalRepository approvals,
                              EffectLedger ledger,
                              FactGathering factGathering,
                              CaseClassifier classifier,
                              PolicyGate policyGate,
                              RefundProvider provider,
                              StateMachineProperties properties,
                              MeterRegistry meters,
                              Clock clock) {
        this.runs = runs;
        this.approvals = approvals;
        this.ledger = ledger;
        this.factGathering = factGathering;
        this.classifier = classifier;
        this.policyGate = policyGate;
        this.provider = provider;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    // --------------------------------------------------------------- start

    public Optional<RefundRun> start(String orderId, String customerMessage) {
        OrderSummary order = factGathering.loadOrder(orderId).orElse(null);
        if (order == null) {
            return Optional.empty();
        }
        String runId = "r-" + UUID.randomUUID().toString().substring(0, 8);
        runs.create(runId, order, customerMessage);
        return Optional.of(advance(runId));
    }

    /**
     * Drives the run as far as it can go without a human. Re-entrant: call it after a restart, after
     * an approval, or from a sweeper.
     */
    public RefundRun advance(String runId) {
        RefundRun run = runs.find(runId).orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
        RunState before = null;

        // Each iteration performs at most one transition; the loop stops when nothing moved, which
        // makes progress explicit and prevents a silent spin.
        while (run.state() != before && !run.state().terminal() && run.state() != RunState.AWAITING_APPROVAL) {
            before = run.state();
            run = switch (run.state()) {
                case CREATED -> gatherFacts(run);
                case FACTS_GATHERED -> classify(run);
                case CLASSIFIED -> route(run);
                case PAYOUT_PENDING -> pay(run);
                case PAID -> notifyCustomer(run);
                case NOTIFIED -> close(run);
                case COMPENSATING -> compensate(run);
                default -> run;
            };
        }
        meters.counter("agentic.fsm.state", "state", run.state().name()).increment();
        return run;
    }

    // ---------------------------------------------------------- transitions

    private RefundRun gatherFacts(RefundRun run) {
        OrderSummary order = factGathering.loadOrder(run.orderId()).orElseThrow();
        CaseFacts facts = factGathering.gather(order);
        runs.transition(run.runId(), RunState.CREATED, RunState.FACTS_GATHERED, "system",
                "order, policy and fraud data gathered",
                Map.of("fraud_signal", facts.fraudSignal().name()));
        return runs.find(run.runId()).orElseThrow();
    }

    private RefundRun classify(RefundRun run) {
        OrderSummary order = factGathering.loadOrder(run.orderId()).orElseThrow();
        CaseFacts facts = factGathering.gather(order);
        String message = runs.customerMessage(run.runId()).orElse("");

        RefundDecision decision = classifier.classify(run.runId(), facts, message);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision_outcome", decision.outcome().name());
        fields.put("decision_clause", decision.clauseId());
        fields.put("decision_risk", decision.risk().name());
        runs.transition(run.runId(), RunState.FACTS_GATHERED, RunState.CLASSIFIED, "model",
                "classified as " + decision.outcome() + " citing " + decision.clauseId(), fields);
        return runs.find(run.runId()).orElseThrow();
    }

    private RefundRun route(RefundRun run) {
        OrderSummary order = factGathering.loadOrder(run.orderId()).orElseThrow();
        CaseFacts facts = factGathering.gather(order);
        RefundDecision decision = new RefundDecision(
                RefundDecision.Outcome.valueOf(run.decisionOutcome()),
                run.decisionClause(), run.amountMinor(),
                io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RiskLevel.valueOf(run.decisionRisk()),
                "persisted");

        PolicyGate.Decision gate = policyGate.decide(facts, decision);
        meters.counter("agentic.fsm.gate", "rule", gate.rule(), "outcome", gate.outcome().name()).increment();

        switch (gate.outcome()) {
            case DENY -> runs.transition(run.runId(), RunState.CLASSIFIED, RunState.DECLINED, "gate",
                    gate.rule(), Map.of("gate_rule", gate.rule()));
            case AUTO -> runs.transition(run.runId(), RunState.CLASSIFIED, RunState.PAYOUT_PENDING, "gate",
                    gate.rule(), Map.of("gate_rule", gate.rule(),
                            "idempotency_key", idempotencyKey(run)));
            case NEEDS_APPROVAL -> {
                // The frozen payload: what a human approves, and what must still match at execution.
                approvals.create(run.runId(), run.orderId(), run.amountMinor(), payloadHash(run),
                        gate.rule(), gate.humanExplanation(), gate.requiredApprovals(),
                        properties.approvalTtl());
                runs.transition(run.runId(), RunState.CLASSIFIED, RunState.AWAITING_APPROVAL, "gate",
                        gate.rule() + " — awaiting " + gate.requiredApprovals() + " approval(s)",
                        Map.of("gate_rule", gate.rule()));
            }
        }
        return runs.find(run.runId()).orElseThrow();
    }

    private RefundRun pay(RefundRun run) {
        String key = run.idempotencyKey() == null ? idempotencyKey(run) : run.idempotencyKey();

        // Phase 1 — intent. The unique key means a concurrent caller cannot also proceed.
        boolean firstAttempt = ledger.recordIntent(key, run.runId(), "issueRefund", run.orderId(),
                run.amountMinor());
        if (!firstAttempt) {
            Optional<EffectLedger.Entry> entry = ledger.find(key);
            if (entry.isPresent() && entry.get().phase() == EffectLedger.Phase.APPLIED) {
                runs.transition(run.runId(), RunState.PAYOUT_PENDING, RunState.PAID, "system",
                        "replayed from the idempotency ledger",
                        Map.of("receipt_id", entry.get().resultRef()));
                return runs.find(run.runId()).orElseThrow();
            }
        }

        try {
            RefundProvider.Receipt receipt = provider.pay(run.orderId(), run.amountMinor(), key);
            ledger.recordOutcome(key, EffectLedger.Phase.APPLIED, receipt.receiptId());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("receipt_id", receipt.receiptId());
            fields.put("settles_at", receipt.settlesAt());
            runs.transition(run.runId(), RunState.PAYOUT_PENDING, RunState.PAID, "system",
                    "refund applied, compensation window open until " + receipt.settlesAt(), fields);
        }
        catch (RuntimeException ex) {
            // The outcome is unknown, not failed: the provider may have applied it. Leave the
            // ledger entry as INTENT so the reconciliation sweeper resolves it against the provider.
            log.warn("run {}: payout attempt failed, leaving it for reconciliation: {}",
                    run.runId(), ex.toString());
            meters.counter("agentic.fsm.payout.unresolved").increment();
            return runs.find(run.runId()).orElseThrow();
        }
        return runs.find(run.runId()).orElseThrow();
    }

    private RefundRun notifyCustomer(RefundRun run) {
        String body = customerReply(run);
        try {
            provider.notifyCustomer(run.customerEmail(), body);
            runs.transition(run.runId(), RunState.PAID, RunState.NOTIFIED, "system",
                    "customer notified", Map.of("customer_reply", body));
        }
        catch (RuntimeException ex) {
            log.warn("run {}: notification failed after payment, compensating: {}", run.runId(), ex.toString());
            runs.transition(run.runId(), RunState.PAID, RunState.COMPENSATING, "system",
                    "notification failed: " + ex.getClass().getSimpleName(),
                    Map.of("failure_reason", "notification failed"));
        }
        return runs.find(run.runId()).orElseThrow();
    }

    private RefundRun close(RefundRun run) {
        runs.transition(run.runId(), RunState.NOTIFIED, RunState.CLOSED, "system", "run complete", Map.of());
        return runs.find(run.runId()).orElseThrow();
    }

    /**
     * Unwind in reverse order, and only what is still inside its window.
     *
     * <p>A compensation that cannot run is an <b>incident</b>, not a retry: the run goes to
     * {@link RunState#NEEDS_MANUAL_INTERVENTION}, which exists so that this case pages a human
     * rather than blending into ordinary failures.
     */
    private RefundRun compensate(RefundRun run) {
        if (run.receiptId() == null) {
            runs.transition(run.runId(), RunState.COMPENSATING, RunState.FAILED, "system",
                    "nothing to compensate", Map.of());
            return runs.find(run.runId()).orElseThrow();
        }
        try {
            provider.cancel(run.idempotencyKey());
            ledger.recordOutcome(run.idempotencyKey(), EffectLedger.Phase.COMPENSATED, run.receiptId());
            meters.counter("agentic.fsm.compensation", "outcome", "SUCCEEDED").increment();
            runs.transition(run.runId(), RunState.COMPENSATING, RunState.FAILED, "system",
                    "refund cancelled inside the compensation window", Map.of());
        }
        catch (RefundProvider.CompensationWindowClosedException ex) {
            meters.counter("agentic.fsm.compensation", "outcome", "WINDOW_CLOSED").increment();
            runs.transition(run.runId(), RunState.COMPENSATING, RunState.NEEDS_MANUAL_INTERVENTION,
                    "system", ex.getMessage(),
                    Map.of("failure_reason", "compensation window closed: " + ex.getMessage()));
        }
        catch (RuntimeException ex) {
            meters.counter("agentic.fsm.compensation", "outcome", "FAILED").increment();
            runs.transition(run.runId(), RunState.COMPENSATING, RunState.NEEDS_MANUAL_INTERVENTION,
                    "system", "compensation failed: " + ex.getMessage(),
                    Map.of("failure_reason", "compensation failed"));
        }
        return runs.find(run.runId()).orElseThrow();
    }

    // -------------------------------------------------------- human actions

    /** Records an approval. On the final approval the frozen payload is executed — with no model turn. */
    public Optional<RefundRun> approve(String approvalId, String approver) {
        ApprovalRepository.Approval approval = approvals.addApprover(approvalId, approver).orElse(null);
        if (approval == null) {
            return Optional.empty();
        }
        if (!approval.fullyApproved()) {
            return runs.find(approval.runId());
        }

        RefundRun run = runs.find(approval.runId()).orElseThrow();
        // The hash must still match: the same order, the same amount as when it was approved.
        if (approvals.consume(approvalId, payloadHash(run)).isEmpty()) {
            runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.FAILED, approver,
                    "approved payload no longer matches the run",
                    Map.of("failure_reason", "payload hash mismatch at execution"));
            return runs.find(run.runId());
        }
        runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.PAYOUT_PENDING, approver,
                "approved by " + String.join(" and ", approval.approvers()),
                Map.of("idempotency_key", idempotencyKey(run)));
        return Optional.of(advance(run.runId()));
    }

    public Optional<RefundRun> reject(String approvalId, String approver) {
        ApprovalRepository.Approval approval = approvals.reject(approvalId, approver).orElse(null);
        if (approval == null) {
            return Optional.empty();
        }
        runs.transition(approval.runId(), RunState.AWAITING_APPROVAL, RunState.REJECTED, approver,
                "rejected by " + approver, Map.of());
        return runs.find(approval.runId());
    }

    /** Called by the expiry sweeper. Expiry means no. */
    public void expire(ApprovalRepository.Approval approval) {
        approvals.expire(approval.approvalId());
        runs.transition(approval.runId(), RunState.AWAITING_APPROVAL, RunState.EXPIRED, "sweeper",
                "no approval within " + properties.approvalTtl(), Map.of());
        meters.counter("agentic.fsm.approval", "outcome", "EXPIRED").increment();
    }

    /**
     * Called by the reconciliation sweeper for a run stuck in {@code PAYOUT_PENDING}: ask the
     * provider whether the idempotency key was applied, and move the run accordingly.
     */
    public RefundRun reconcile(RefundRun run) {
        String key = run.idempotencyKey();
        Optional<RefundProvider.Receipt> receipt = key == null
                ? Optional.empty() : provider.statusFor(key);
        if (receipt.isPresent()) {
            ledger.recordOutcome(key, EffectLedger.Phase.APPLIED, receipt.get().receiptId());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("receipt_id", receipt.get().receiptId());
            fields.put("settles_at", receipt.get().settlesAt());
            runs.transition(run.runId(), RunState.PAYOUT_PENDING, RunState.PAID, "reconciler",
                    "provider confirms the payment was applied", fields);
            meters.counter("agentic.fsm.reconciliation", "outcome", "APPLIED").increment();
            return advance(run.runId());
        }
        if (key != null) {
            ledger.recordOutcome(key, EffectLedger.Phase.FAILED, null);
        }
        runs.transition(run.runId(), RunState.PAYOUT_PENDING, RunState.FAILED, "reconciler",
                "provider confirms no payment was applied", Map.of("failure_reason", "payout failed"));
        meters.counter("agentic.fsm.reconciliation", "outcome", "NOT_APPLIED").increment();
        return runs.find(run.runId()).orElseThrow();
    }

    // ------------------------------------------------------------ internals

    /**
     * The customer reply is a template, not a generated message. Project 06 shows the
     * evaluator–optimiser version; here the subject is state, and a deterministic reply keeps the
     * state diagram readable.
     */
    private String customerReply(RefundRun run) {
        return ("Thank you for contacting us about order %s. We have completed our review and the "
                + "outcome has been applied to your original payment method.").formatted(run.orderId());
    }

    private String payloadHash(RefundRun run) {
        return Hashing.payloadHash(run.runId(), run.orderId(), run.amountMinor());
    }

    private String idempotencyKey(RefundRun run) {
        return Hashing.idempotencyKey(run.runId(), "issueRefund", run.orderId(), run.amountMinor());
    }
}
