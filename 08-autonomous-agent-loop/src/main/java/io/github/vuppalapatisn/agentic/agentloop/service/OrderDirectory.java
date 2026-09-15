package io.github.vuppalapatisn.agentic.agentloop.service;

import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.OrderSummary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Trusted order facts and the {@code R1} fraud boundary. Same seed data as projects 06 and 07. */
@Component
public class OrderDirectory {

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();
    private final Map<String, String> fraudResponses = new LinkedHashMap<>();

    public OrderDirectory(Clock clock) {
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

    public Optional<OrderSummary> find(String orderId) {
        if (orderId == null || !orderId.matches("[A-Z]-\\d{4}")) {
            throw new IllegalArgumentException("orderId must look like A-1187");
        }
        return Optional.ofNullable(orders.get(orderId));
    }

    public OrderSummary require(String orderId) {
        return find(orderId).orElseThrow(() -> new IllegalArgumentException("unknown order " + orderId));
    }

    /** The provider's free text never leaves this method. */
    public FraudSignal fraudSignal(String orderId) {
        String body = fraudResponses.get(require(orderId).customerId());
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
