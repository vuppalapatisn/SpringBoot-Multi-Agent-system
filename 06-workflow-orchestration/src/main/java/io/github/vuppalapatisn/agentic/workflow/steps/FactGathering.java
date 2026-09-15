package io.github.vuppalapatisn.agentic.workflow.steps;

import io.github.vuppalapatisn.agentic.workflow.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.PolicyClause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stage 1–2 of the workflow: read the order, then gather policy and fraud data <b>in parallel</b>.
 *
 * <p>This is the fan-out/fan-in pattern. The two lookups are independent, so running them
 * concurrently turns two sequential latencies into one — the cheapest performance win available in
 * a workflow, and one an agent loop cannot make because it does not know in advance that the two
 * calls are independent.
 *
 * <p>Both branches have a timeout, and the fraud branch <b>degrades</b> to
 * {@link FraudSignal#UNAVAILABLE} rather than failing the run. Policy is authoritative, so a policy
 * failure is a run failure; risk data is advisory, and an unavailable signal simply means the run
 * cannot be auto-approved.
 */
@Component
public class FactGathering {

    private static final Logger log = LoggerFactory.getLogger(FactGathering.class);
    private static final Duration BRANCH_TIMEOUT = Duration.ofSeconds(3);

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();
    private final Map<String, String> fraudResponses = new LinkedHashMap<>();
    private final ExecutorService workflowExecutor;
    private final Clock clock;

    public FactGathering(ExecutorService workflowExecutor, Clock clock) {
        this.workflowExecutor = workflowExecutor;
        this.clock = clock;
        seed();
    }

    private void seed() {
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

    /** Stage 1 — {@code R0}. */
    public Optional<OrderSummary> loadOrder(String orderId) {
        if (orderId == null || !orderId.matches("[A-Z]-\\d{4}")) {
            throw new IllegalArgumentException("orderId must look like A-1187");
        }
        return Optional.ofNullable(orders.get(orderId));
    }

    /** Stage 2 — parallel fan-out, deterministic fan-in. */
    public CaseFacts gather(OrderSummary order) {
        CompletableFuture<List<PolicyClause>> policy =
                CompletableFuture.supplyAsync(() -> lookupPolicy(order), workflowExecutor);
        CompletableFuture<FraudSignal> fraud =
                CompletableFuture.supplyAsync(() -> checkFraud(order), workflowExecutor);

        List<PolicyClause> clauses = join(policy, "policy");
        FraudSignal signal = joinOrDefault(fraud, FraudSignal.UNAVAILABLE);

        return new CaseFacts(order, clauses, signal, order.ageInDays(LocalDate.now(clock)));
    }

    /** {@code R0} — deterministic policy lookup. Project 03 does the retrieval-based version. */
    List<PolicyClause> lookupPolicy(OrderSummary order) {
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

    /** {@code R1} — the taint boundary. The provider's free text never leaves this method. */
    FraudSignal checkFraud(OrderSummary order) {
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

    private <T> T join(CompletableFuture<T> future, String branch) {
        try {
            return future.get(BRANCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted gathering " + branch, ex);
        }
        catch (TimeoutException | java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("failed gathering " + branch + ": " + ex, ex);
        }
    }

    private <T> T joinOrDefault(CompletableFuture<T> future, T fallback) {
        try {
            return future.get(BRANCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback;
        }
        catch (Exception ex) {
            // Advisory data: degrade rather than fail. An UNAVAILABLE signal is not LOW risk, so
            // the run simply cannot be auto-approved.
            log.warn("fraud branch degraded to {}: {}", fallback, ex.toString());
            return fallback;
        }
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
