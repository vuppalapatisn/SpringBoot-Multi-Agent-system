package io.github.vuppalapatisn.agentic.foundation.service;

import io.github.vuppalapatisn.agentic.foundation.domain.OrderSummary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Trusted order facts — an {@code R0} read from our own system.
 *
 * <p>Seeded in memory so the project runs without a database. The important property is not where
 * the data lives but that it is the <b>source of truth for amounts</b>: a downstream gate reads the
 * total from here, never from model output.
 */
@Component
public class OrderDirectory {

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();

    public OrderDirectory() {
        LocalDate today = LocalDate.now();
        seed(new OrderSummary("A-1187", "c-5512", 24_000L, "USD",
                today.minusDays(9), "DELIVERED", "Noise-cancelling headphones"));
        seed(new OrderSummary("A-1204", "c-5512", 8_990L, "USD",
                today.minusDays(3), "DELIVERED", "USB-C cable, 2m"));
        seed(new OrderSummary("A-0988", "c-7731", 189_900L, "USD",
                today.minusDays(58), "DELIVERED", "Espresso machine"));
        seed(new OrderSummary("A-1310", "c-4410", 4_500L, "USD",
                today.minusDays(1), "IN_TRANSIT", "Phone case"));
    }

    private void seed(OrderSummary order) {
        orders.put(order.orderId(), order);
    }

    public Optional<OrderSummary> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
