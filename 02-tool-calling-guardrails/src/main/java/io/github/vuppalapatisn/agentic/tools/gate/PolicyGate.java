package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import io.github.vuppalapatisn.agentic.tools.domain.FraudSignal;
import io.github.vuppalapatisn.agentic.tools.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.tools.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The tiered policy gate. <b>Plain Java. Deliberately not an advisor.</b>
 *
 * <p>Advisors wrap the model call; a gate must wrap the effect. If this were an advisor, any code
 * path that called the tool bean directly would bypass it. Instead it is invoked inside the tool
 * boundary, and {@link GuardedToolExecutor} refuses to run an irreversible effect that did not come
 * through it.
 *
 * <p>Tiers:
 *
 * <pre>
 *   amount &lt; autoApproveBelowMinor AND risk LOW AND age ≤ maxRefundAgeDays  → AUTO
 *   amount &gt; dualControlAboveMinor OR risk HIGH                            → DUAL CONTROL
 *   otherwise                                                                → SINGLE APPROVER
 *   order not in a refundable state                                          → DENY
 * </pre>
 *
 * <p>Every branch is decided from <b>trusted</b> inputs: the order record and an enum-valued fraud
 * signal. Nothing the model wrote reaches this method.
 */
@Component
public class PolicyGate {

    private final GuardrailProperties properties;
    private final Clock clock;

    public PolicyGate(GuardrailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public GateDecision decideRefund(OrderSummary order, FraudSignal signal) {
        if (!"DELIVERED".equals(order.status())) {
            return GateDecision.deny("ORDER_NOT_DELIVERED",
                    "Order %s is %s. Policy RP-IN-TRANSIT forbids refunding an order that has not been delivered."
                            .formatted(order.orderId(), order.status()));
        }

        int ageDays = order.ageInDays(LocalDate.now(clock));
        RiskLevel risk = signal.risk();
        long amount = order.totalMinor();

        if (amount > properties.dualControlAboveMinor() || risk == RiskLevel.HIGH) {
            return GateDecision.dual("DUAL_CONTROL_HIGH_VALUE_OR_RISK", explain(order, signal, ageDays,
                    amount > properties.dualControlAboveMinor()
                            ? "above the dual-control threshold"
                            : "flagged high risk"));
        }

        if (amount < properties.autoApproveBelowMinor()
                && risk == RiskLevel.LOW
                && ageDays <= properties.maxRefundAgeDays()) {
            return GateDecision.auto("AUTO_LOW_VALUE_LOW_RISK", explain(order, signal, ageDays,
                    "within the automatic tier"));
        }

        return GateDecision.single("SINGLE_APPROVER_DEFAULT", explain(order, signal, ageDays,
                "outside the automatic tier"));
    }

    /**
     * The approver-facing text. Rendered here, from trusted data, on purpose: if the model writes
     * the approval summary then the model can lie in the approval summary.
     */
    private String explain(OrderSummary order, FraudSignal signal, int ageDays, String because) {
        return """
                Refund %s to customer %s
                Order %s · %s · placed %s (%d days ago) · status %s
                Fraud signal: %s (risk %s)
                Gate: %s"""
                .formatted(order.formattedTotal(), order.customerId(),
                        order.orderId(), order.itemDescription(), order.placedOn(), ageDays, order.status(),
                        signal, signal.risk(), because);
    }
}
