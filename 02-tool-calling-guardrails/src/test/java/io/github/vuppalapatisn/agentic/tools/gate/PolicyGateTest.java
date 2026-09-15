package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.config.ExecutionMode;
import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import io.github.vuppalapatisn.agentic.tools.domain.FraudSignal;
import io.github.vuppalapatisn.agentic.tools.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.tools.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary tests for the tiers. Note that these assert on the <b>gate</b>, not on the model:
 * thresholds are exactly the thing a model must not be trusted to apply.
 */
class PolicyGateTest {

    private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);

    private final GuardrailProperties properties = new GuardrailProperties(
            ExecutionMode.EXECUTE, Set.of("issueRefund"),
            10_000L,        // auto below $100.00
            100_000L,       // dual control above $1,000.00
            30,
            Duration.ofHours(24), 2, List.of("@customers.example"));

    private final PolicyGate gate = new PolicyGate(properties, clock);

    private OrderSummary order(long totalMinor, int ageDays, String status) {
        return new OrderSummary("A-1187", "c-5512", "ana@customers.example", totalMinor, "USD",
                LocalDate.ofInstant(NOW, clock.getZone()).minusDays(ageDays), status, "Headphones");
    }

    @Test
    @DisplayName("$99.99, low risk, day 9 → automatic: no human is interrupted")
    void autoTier() {
        GateDecision decision = gate.decideRefund(order(9_999L, 9, "DELIVERED"), FraudSignal.CLEAN);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.AUTO);
        assertThat(decision.rule()).isEqualTo("AUTO_LOW_VALUE_LOW_RISK");
        assertThat(decision.requiredApprovals()).isZero();
    }

    @Test
    @DisplayName("$100.00 exactly → outside the automatic tier: the boundary is closed, not open")
    void autoTierBoundaryIsExclusive() {
        GateDecision decision = gate.decideRefund(order(10_000L, 9, "DELIVERED"), FraudSignal.CLEAN);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.NEEDS_APPROVAL);
        assertThat(decision.requiredApprovals()).isEqualTo(1);
    }

    @Test
    @DisplayName("day 31 → outside the window even at a low amount")
    void outOfWindowNeedsAHuman() {
        GateDecision decision = gate.decideRefund(order(5_000L, 31, "DELIVERED"), FraudSignal.CLEAN);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.NEEDS_APPROVAL);
    }

    @Test
    @DisplayName("above the dual-control threshold → two approvers")
    void dualControlOnValue() {
        GateDecision decision = gate.decideRefund(order(189_900L, 5, "DELIVERED"), FraudSignal.CLEAN);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.NEEDS_APPROVAL);
        assertThat(decision.requiredApprovals()).isEqualTo(2);
        assertThat(decision.rule()).isEqualTo("DUAL_CONTROL_HIGH_VALUE_OR_RISK");
    }

    @Test
    @DisplayName("high fraud risk → two approvers even for a small amount")
    void dualControlOnRisk() {
        GateDecision decision = gate.decideRefund(order(2_000L, 1, "DELIVERED"), FraudSignal.VELOCITY_ABUSE);

        assertThat(decision.requiredApprovals()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unavailable fraud provider is treated as not-low risk, so it cannot auto-approve")
    void unavailableSignalFailsTowardsCaution() {
        GateDecision decision = gate.decideRefund(order(1_000L, 1, "DELIVERED"), FraudSignal.UNAVAILABLE);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.NEEDS_APPROVAL);
    }

    @Test
    @DisplayName("an undelivered order is denied, and a denial is not an escalation")
    void inTransitIsDenied() {
        GateDecision decision = gate.decideRefund(order(4_500L, 1, "IN_TRANSIT"), FraudSignal.CLEAN);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.DENY);
        assertThat(decision.requiredApprovals()).isZero();
    }

    @Test
    @DisplayName("the approver-facing explanation is rendered from trusted data")
    void explanationComesFromTheOrderRecord() {
        GateDecision decision = gate.decideRefund(order(24_000L, 9, "DELIVERED"), FraudSignal.CLEAN);

        assertThat(decision.humanExplanation())
                .contains("USD 240.00")
                .contains("A-1187")
                .contains("c-5512")
                .contains("CLEAN");
    }
}
