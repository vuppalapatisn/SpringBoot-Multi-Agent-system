package io.github.vuppalapatisn.agentic.tools.provider;

import io.github.vuppalapatisn.agentic.tools.domain.OrderSummary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Trusted order facts ({@code R0}), seeded in memory so the project runs with no database. */
@Component
public class OrderDirectory {

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();

    public OrderDirectory(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        // low value, low risk, in window  -> automatic tier
        seed(new OrderSummary("A-1204", "c-5512", "ana@customers.example", 8_990L, "USD",
                today.minusDays(3), "DELIVERED", "USB-C cable, 2m"));
        // mid value, low risk              -> single approver
        seed(new OrderSummary("A-1187", "c-5512", "ana@customers.example", 24_000L, "USD",
                today.minusDays(9), "DELIVERED", "Noise-cancelling headphones"));
        // high value, watchlist            -> dual control
        seed(new OrderSummary("A-0988", "c-7731", "bo@customers.example", 189_900L, "USD",
                today.minusDays(12), "DELIVERED", "Espresso machine"));
        // in transit                       -> denied by policy
        seed(new OrderSummary("A-1310", "c-4410", "cy@customers.example", 4_500L, "USD",
                today.minusDays(1), "IN_TRANSIT", "Phone case"));
        // hostile fraud-provider payload   -> see FraudService
        seed(new OrderSummary("A-1400", "c-9001", "dee@customers.example", 96_000L, "USD",
                today.minusDays(5), "DELIVERED", "Tablet"));
    }

    private void seed(OrderSummary order) {
        orders.put(order.orderId(), order);
    }

    public Optional<OrderSummary> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public OrderSummary require(String orderId) {
        return find(orderId).orElseThrow(() -> new IllegalArgumentException("unknown order " + orderId));
    }
}
