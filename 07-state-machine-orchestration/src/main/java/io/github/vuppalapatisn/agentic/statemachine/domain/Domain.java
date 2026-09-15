package io.github.vuppalapatisn.agentic.statemachine.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Shared domain shapes. Identical vocabulary to projects 06, 08 and 09 so the diff is the design. */
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

    public record PolicyClause(String clauseId, String summary, int windowDays) {
    }

    public record CaseFacts(OrderSummary order, List<PolicyClause> clauses,
                            FraudSignal fraudSignal, int ageInDays) {
    }

    /**
     * A persisted run. This is the state machine's position plus everything needed to resume from
     * it — which is what makes "durable" true rather than aspirational.
     */
    public record RefundRun(
            String runId,
            String orderId,
            String customerId,
            String customerEmail,
            long amountMinor,
            String currency,
            RunState state,
            String gateRule,
            String decisionOutcome,
            String decisionClause,
            String decisionRisk,
            String fraudSignal,
            String customerReply,
            String receiptId,
            String idempotencyKey,
            Instant settlesAt,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {

        /** True while a refund can still be cancelled. After this, the effect is irreversible in fact. */
        public boolean compensable(Instant now) {
            return settlesAt != null && now.isBefore(settlesAt);
        }
    }

    public record Transition(int seq, RunState from, RunState to, String actor, String reason, Instant at) {
    }
}
