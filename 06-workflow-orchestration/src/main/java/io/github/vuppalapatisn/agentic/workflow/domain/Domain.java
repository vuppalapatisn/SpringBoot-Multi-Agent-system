package io.github.vuppalapatisn.agentic.workflow.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Shared domain shapes for the Refund Desk.
 *
 * <p>Projects 06–09 solve the <b>same</b> problem with four different architectures, so they share
 * these shapes deliberately: when you diff the projects, the difference you see is the
 * orchestration, not the vocabulary.
 */
public final class Domain {

    private Domain() {
    }

    public record OrderSummary(
            String orderId,
            String customerId,
            String customerEmail,
            long totalMinor,
            String currency,
            LocalDate placedOn,
            String status,
            String itemDescription) {

        public int ageInDays(LocalDate today) {
            return (int) ChronoUnit.DAYS.between(placedOn, today);
        }

        public String formattedTotal() {
            return "%s %d.%02d".formatted(currency, totalMinor / 100, Math.abs(totalMinor % 100));
        }
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    /** The closed vocabulary an external fraud provider's answer is mapped onto. */
    public enum FraudSignal {

        CLEAN(RiskLevel.LOW),
        WATCHLIST(RiskLevel.MEDIUM),
        VELOCITY_ABUSE(RiskLevel.HIGH),
        CHARGEBACK_HISTORY(RiskLevel.HIGH),
        UNAVAILABLE(RiskLevel.MEDIUM);

        private final RiskLevel risk;

        FraudSignal(RiskLevel risk) {
            this.risk = risk;
        }

        public RiskLevel risk() {
            return risk;
        }
    }

    /** A policy clause, looked up deterministically rather than retrieved. */
    public record PolicyClause(String clauseId, String summary, int windowDays) {
    }

    /**
     * Everything gathered before the model is asked anything — the fan-in of the parallel stage.
     * A single immutable value means the classification step cannot accidentally depend on I/O.
     */
    public record CaseFacts(
            OrderSummary order,
            List<PolicyClause> clauses,
            FraudSignal fraudSignal,
            int ageInDays) {
    }

    /** Every way a run can end. Exhaustive, so no path can be forgotten. */
    public enum RunStatus {
        PAID,
        DECLINED,
        AWAITING_APPROVAL,
        APPROVED_AND_PAID,
        REJECTED,
        FAILED
    }

    /**
     * The result of one workflow run.
     *
     * @param runId        correlation id
     * @param status       terminal state
     * @param decision     the model's classification, reconciled against trusted data
     * @param gateRule     the rule that decided whether money could move
     * @param amountMinor  the authoritative amount, from the order record
     * @param receiptId    provider reference, when money moved
     * @param approvalId   pending approval, when a human is needed
     * @param customerReply the drafted message, not yet sent
     * @param steps        the executed step names, in order — the workflow's own trace
     * @param took         wall clock
     * @param at           when the run finished
     */
    public record RefundRunResult(
            String runId,
            RunStatus status,
            RefundDecision decision,
            String gateRule,
            long amountMinor,
            String receiptId,
            String approvalId,
            String customerReply,
            List<String> steps,
            Duration took,
            Instant at) {
    }
}
