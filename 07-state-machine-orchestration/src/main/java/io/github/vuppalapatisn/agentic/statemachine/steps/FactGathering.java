package io.github.vuppalapatisn.agentic.statemachine.steps;

import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.PolicyClause;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Order, policy and fraud lookups. Same work as project 06's stage 1–2; the parallel fan-out is
 * omitted here because this project's subject is state, not latency, and mixing the two would make
 * the diff between 06 and 07 harder to read.
 */
@Component
public class FactGathering {

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();
    private final Map<String, String> fraudResponses = new LinkedHashMap<>();
    private final Clock clock;

    public FactGathering(Clock clock) {
        this.clock = clock;
        LocalDate today = LocalDate.now(clock);
        put(new OrderSummary("A-1204", "c-5512", "ana@customers.example", 8_990L, "USD",
                today.minusDays(3), "DELIVERED", "USB-C cable, 2m"));
        put(new OrderSummary("A-1187", "c-5512", "ana@customers.example", 24_000L, "USD",
                today.minusDays(9), "DELIVERED", "Noise-cancelling headphones"));
        put(new OrderSummary("A-0988", "c-7731", "bo@customers.example", 189_900L, "USD",
                today.minusDays(12), "DELIVERED", "Espresso machine"));
        put(new OrderSummary("A-1310", "c-4410", "cy@customers.example", 4_500L, "USD",
                today.minusDays(1), "IN_TRANSIT", "Phone case"));

        fraudResponses.put("c-5512", "{\"verdict\":\"clean\"}");
        fraudResponses.put("c-7731", "{\"verdict\":\"watchlist\"}");
        fraudResponses.put("c-4410", "{\"verdict\":\"velocity_abuse\"}");
    }

    private void put(OrderSummary order) {
        orders.put(order.orderId(), order);
    }

    public Optional<OrderSummary> loadOrder(String orderId) {
        if (orderId == null || !orderId.matches("[A-Z]-\\d{4}")) {
            throw new IllegalArgumentException("orderId must look like A-1187");
        }
        return Optional.ofNullable(orders.get(orderId));
    }

    public CaseFacts gather(OrderSummary order) {
        return new CaseFacts(order, policyClauses(), checkFraud(order),
                order.ageInDays(LocalDate.now(clock)));
    }

    public List<PolicyClause> policyClauses() {
        return List.of(
                new PolicyClause("RP-30D-NOT-RECEIVED",
                        "A delivered order reported as not received may be refunded in full within 30 days.", 30),
                new PolicyClause("RP-30D-DAMAGED",
                        "A delivered order reported as damaged or faulty may be refunded in full within 30 days.", 30),
                new PolicyClause("RP-CHANGE-OF-MIND",
                        "Change of mind is refundable in full within 14 days if unopened.", 14),
                new PolicyClause("RP-IN-TRANSIT",
                        "An order that has not been delivered is never refunded.", 0),
                new PolicyClause("RP-ESCALATE-LEGAL",
                        "Any mention of legal action, a chargeback or a regulator is escalated, never refunded automatically.", 0));
    }

    /** {@code R1} taint boundary: the provider's free text never leaves this method. */
    public FraudSignal checkFraud(OrderSummary order) {
        String body = fraudResponses.get(order.customerId());
        if (body == null) {
            return FraudSignal.UNAVAILABLE;
        }
        return switch (verdictOf(body)) {
            case "clean" -> FraudSignal.CLEAN;
            case "watchlist" -> FraudSignal.WATCHLIST;
            case "velocity_abuse" -> FraudSignal.VELOCITY_ABUSE;
            case "chargeback_history" -> FraudSignal.CHARGEBACK_HISTORY;
            default -> FraudSignal.UNAVAILABLE;
        };
    }

    private static String verdictOf(String body) {
        int at = body.indexOf("\"verdict\":\"");
        if (at < 0) {
            return "";
        }
        int start = at + "\"verdict\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? "" : body.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
