package io.github.vuppalapatisn.agentic.mcpserver.service;

import io.github.vuppalapatisn.agentic.mcpserver.config.ServerProperties;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.RefundOutcome;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.RiskLevel;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.ServerOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All server-side business logic and policy, in one place, deliberately independent of MCP.
 *
 * <p><b>The rule this project exists to demonstrate: a server must not rely on the client to
 * enforce policy.</b> The caller is an LLM agent you do not control, running a prompt you have not
 * read, possibly influenced by text an attacker wrote. Every control that matters is re-applied
 * here:
 *
 * <ul>
 *   <li>the refund amount comes from the order record, so the tool needs no amount parameter;</li>
 *   <li>the tiered policy is evaluated server-side, whatever the client believes;</li>
 *   <li>an irreversible action returns {@code APPROVAL_REQUIRED} rather than happening;</li>
 *   <li>rate limits are counted per caller and per order, here, not there;</li>
 *   <li>idempotency keys are derived here, from durable facts.</li>
 * </ul>
 */
@Service
public class RefundDesk {

    private static final Logger log = LoggerFactory.getLogger(RefundDesk.class);

    /** A pending approval. Grantable only out of band — never by the MCP caller. */
    public record PendingApproval(String id, String orderId, long amountMinor, String rule,
                                  String explanation, int requiredApprovals, List<String> approvers,
                                  boolean executed) {
    }

    private final Map<String, OrderSummary> orders = new LinkedHashMap<>();
    private final Map<String, String> fraudResponses = new LinkedHashMap<>();
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final Map<String, String> receiptsByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsPerOrder = new ConcurrentHashMap<>();

    private final ServerProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public RefundDesk(ServerProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        seed();
    }

    private void seed() {
        LocalDate today = LocalDate.now(clock);
        put(new OrderSummary("A-1204", "c-5512", 8_990L, "USD", today.minusDays(3), "DELIVERED", "USB-C cable, 2m"));
        put(new OrderSummary("A-1187", "c-5512", 24_000L, "USD", today.minusDays(9), "DELIVERED", "Noise-cancelling headphones"));
        put(new OrderSummary("A-0988", "c-7731", 189_900L, "USD", today.minusDays(12), "DELIVERED", "Espresso machine"));
        put(new OrderSummary("A-1310", "c-4410", 4_500L, "USD", today.minusDays(1), "IN_TRANSIT", "Phone case"));

        fraudResponses.put("c-5512", "{\"verdict\":\"clean\"}");
        fraudResponses.put("c-7731", "{\"verdict\":\"watchlist\",\"reason\":\"3 refunds in 60 days\"}");
        fraudResponses.put("c-4410", "{\"verdict\":\"velocity_abuse\"}");
    }

    private void put(OrderSummary order) {
        orders.put(order.orderId(), order);
    }

    // ------------------------------------------------------------------ reads

    public Optional<OrderSummary> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(validOrderId(orderId)));
    }

    /**
     * The {@code R1} taint boundary, on the server side. The partner's free-text fields never leave
     * this method: the client receives an enum constant.
     */
    public FraudSignal fraudSignal(String orderId) {
        OrderSummary order = findOrder(orderId).orElseThrow(() -> new IllegalArgumentException("unknown order"));
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

    // ------------------------------------------------------- the one-way door

    /**
     * Attempts a refund. Never performs one unless a server-side rule fully authorises it.
     *
     * @param orderId the business key; there is deliberately no amount parameter
     * @param caller  identity asserted by the transport, used for rate limiting and the audit trail
     */
    public RefundOutcome issueRefund(String orderId, String caller) {
        String id = validOrderId(orderId);
        OrderSummary order = orders.get(id);
        if (order == null) {
            return outcome(ServerOutcome.REFUSED, "Unknown order " + id, null, null, 0L);
        }

        if (!properties.executionEnabled()) {
            return outcome(ServerOutcome.REFUSED,
                    "Refund execution is disabled on this server.", null, null, order.totalMinor());
        }

        int calls = callsPerOrder.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
        if (calls > properties.maxRefundAttemptsPerOrder()) {
            log.warn("caller {} exceeded refund attempts for order {}", caller, id);
            return outcome(ServerOutcome.REFUSED,
                    "Too many refund attempts for this order.", null, null, order.totalMinor());
        }

        if (!"DELIVERED".equals(order.status())) {
            return outcome(ServerOutcome.DECLINED,
                    "Order %s is %s; an undelivered order is never refunded."
                            .formatted(id, order.status()), null, null, order.totalMinor());
        }

        // Idempotency: derived from durable facts, checked before anything irreversible happens.
        String key = idempotencyKey(id, order.totalMinor());
        String existing = receiptsByKey.get(key);
        if (existing != null) {
            return outcome(ServerOutcome.REPLAYED,
                    "This refund was already issued.", null, existing, order.totalMinor());
        }

        FraudSignal signal = fraudSignal(id);
        int ageDays = order.ageInDays(LocalDate.now(clock));

        boolean dualControl = order.totalMinor() > properties.dualControlAboveMinor()
                || signal.risk() == RiskLevel.HIGH;
        boolean automatic = !dualControl
                && order.totalMinor() < properties.autoApproveBelowMinor()
                && signal.risk() == RiskLevel.LOW
                && ageDays <= properties.maxRefundAgeDays();

        if (automatic) {
            String receiptId = "re_" + key.substring(0, 10);
            receiptsByKey.put(key, receiptId);
            meters.counter("agentic.mcp.refund", "outcome", "APPLIED", "rule", "AUTO_LOW_VALUE_LOW_RISK")
                    .increment();
            return outcome(ServerOutcome.APPLIED,
                    "Refunded %s for order %s under the automatic tier."
                            .formatted(order.formattedTotal(), id), null, receiptId, order.totalMinor());
        }

        String rule = dualControl ? "DUAL_CONTROL_HIGH_VALUE_OR_RISK" : "SINGLE_APPROVER_DEFAULT";
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8),
                id, order.totalMinor(), rule,
                explain(order, signal, ageDays, rule),
                dualControl ? 2 : 1, List.of(), false);
        approvals.put(approval.id(), approval);
        meters.counter("agentic.mcp.refund", "outcome", "APPROVAL_REQUIRED", "rule", rule).increment();

        return outcome(ServerOutcome.APPROVAL_REQUIRED,
                ("A human approval is required by rule %s and this server will not accept it from a "
                        + "tool caller. Approval %s is pending; nothing has been paid.")
                        .formatted(rule, approval.id()),
                approval.id(), null, order.totalMinor());
    }

    // ----------------------------------------------- out-of-band approvals

    /**
     * Grants an approval. Reachable only from the admin HTTP surface, never as an MCP tool:
     * the caller that requests an action must not be able to authorise it.
     */
    public RefundOutcome approve(String approvalId, String approver) {
        PendingApproval approval = approvals.get(approvalId);
        if (approval == null) {
            return outcome(ServerOutcome.REFUSED, "Unknown approval " + approvalId, null, null, 0L);
        }
        if (approval.executed()) {
            return outcome(ServerOutcome.REFUSED,
                    "Approval " + approvalId + " has already been executed.", approvalId, null,
                    approval.amountMinor());
        }
        if (approval.approvers().contains(approver)) {
            return outcome(ServerOutcome.REFUSED,
                    approver + " has already approved; dual control needs distinct approvers.",
                    approvalId, null, approval.amountMinor());
        }

        List<String> approvers = java.util.stream.Stream
                .concat(approval.approvers().stream(), java.util.stream.Stream.of(approver)).toList();
        if (approvers.size() < approval.requiredApprovals()) {
            approvals.put(approvalId, new PendingApproval(approval.id(), approval.orderId(),
                    approval.amountMinor(), approval.rule(), approval.explanation(),
                    approval.requiredApprovals(), approvers, false));
            return outcome(ServerOutcome.APPROVAL_REQUIRED,
                    "%d of %d approvals collected.".formatted(approvers.size(), approval.requiredApprovals()),
                    approvalId, null, approval.amountMinor());
        }

        OrderSummary order = orders.get(approval.orderId());
        if (order == null || order.totalMinor() != approval.amountMinor()) {
            return outcome(ServerOutcome.REFUSED,
                    "The order changed after this approval was raised; refusing to execute it.",
                    approvalId, null, approval.amountMinor());
        }

        String key = idempotencyKey(approval.orderId(), approval.amountMinor());
        String receiptId = receiptsByKey.computeIfAbsent(key, k -> "re_" + k.substring(0, 10));
        approvals.put(approvalId, new PendingApproval(approval.id(), approval.orderId(),
                approval.amountMinor(), approval.rule(), approval.explanation(),
                approval.requiredApprovals(), approvers, true));
        meters.counter("agentic.mcp.refund", "outcome", "APPLIED", "rule", approval.rule()).increment();

        return outcome(ServerOutcome.APPLIED,
                "Refunded %s for order %s after %d approval(s)."
                        .formatted(order.formattedTotal(), order.orderId(), approvers.size()),
                approvalId, receiptId, order.totalMinor());
    }

    public List<PendingApproval> pendingApprovals() {
        return approvals.values().stream().filter(approval -> !approval.executed()).toList();
    }

    public Optional<String> receiptFor(String orderId) {
        return findOrder(orderId)
                .map(order -> idempotencyKey(order.orderId(), order.totalMinor()))
                .map(receiptsByKey::get);
    }

    // ------------------------------------------------------------- internals

    private RefundOutcome outcome(ServerOutcome outcome, String explanation, String approvalId,
                                  String receiptId, long amountMinor) {
        return new RefundOutcome(outcome, explanation, approvalId, receiptId, amountMinor, clock.instant());
    }

    private String explain(OrderSummary order, FraudSignal signal, int ageDays, String rule) {
        return """
                Refund %s for order %s (customer %s)
                %s · placed %s (%d days ago) · status %s
                Fraud signal: %s (risk %s)
                Rule: %s"""
                .formatted(order.formattedTotal(), order.orderId(), order.customerId(),
                        order.itemDescription(), order.placedOn(), ageDays, order.status(),
                        signal, signal.risk(), rule);
    }

    private String idempotencyKey(String orderId, long amountMinor) {
        return sha256("issueRefund|" + orderId + "|" + amountMinor);
    }

    /** Opaque identifiers are validated, not escaped. */
    private static String validOrderId(String orderId) {
        if (orderId == null || !orderId.matches("[A-Z]-\\d{4}")) {
            throw new IllegalArgumentException("orderId must look like A-1187");
        }
        return orderId;
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

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
