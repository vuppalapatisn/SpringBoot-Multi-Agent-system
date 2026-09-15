package io.github.vuppalapatisn.agentic.mcpserver.tools;

import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.RefundOutcome;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.ServerOutcome;
import io.github.vuppalapatisn.agentic.mcpserver.service.RefundDesk;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The MCP tool surface. A thin adapter over {@link RefundDesk} — all policy lives there.
 *
 * <h2>Two things to understand about MCP tool metadata</h2>
 *
 * <p><b>1. Annotations are a contract, not a control.</b> {@code readOnlyHint},
 * {@code destructiveHint} and {@code idempotentHint} tell a well-behaved client how to treat a tool
 * — many agent frameworks use them to decide what needs confirmation. Declare them honestly,
 * because a client may rely on them. Then enforce everything server-side anyway, because a client
 * may ignore them. The hints below are accurate, and they are not what stops a refund.
 *
 * <p><b>2. Tool descriptions are security surface.</b> A description is text you inject into
 * someone else's model context. Keep it factual and free of instructions: a description that says
 * "always call this first" is indistinguishable from prompt injection, and a client that treats
 * descriptions as untrusted — as project 05 does — will reasonably distrust yours.
 */
@Component
public class RefundMcpTools {

    private final RefundDesk refundDesk;

    public RefundMcpTools(RefundDesk refundDesk) {
        this.refundDesk = refundDesk;
    }

    @McpTool(name = "lookupOrder",
            title = "Look up an order",
            description = "Return the trusted facts about one order: total, currency, status, order date and item.",
            annotations = @McpTool.McpAnnotations(
                    title = "Look up an order",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public OrderSummary lookupOrder(
            @McpToolParam(description = "Order identifier such as A-1187", required = true)
            String orderId) {
        return refundDesk.findOrder(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order " + orderId));
    }

    @McpTool(name = "checkFraudSignal",
            title = "Check the fraud signal for an order's customer",
            description = """
                    Return one of CLEAN, WATCHLIST, VELOCITY_ABUSE, CHARGEBACK_HISTORY, UNAVAILABLE \
                    for the customer who placed an order. The upstream provider's free-text fields are \
                    not returned.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Check fraud signal",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    // Truthful: this reaches a third-party provider.
                    openWorldHint = true))
    public FraudSignal checkFraudSignal(
            @McpToolParam(description = "Order identifier such as A-1187", required = true)
            String orderId) {
        return refundDesk.fraudSignal(orderId);
    }

    /**
     * The one-way door, exposed over a network boundary to a caller we do not control.
     *
     * <p>Note the signature: <b>no amount parameter</b>. The amount is read from the order record,
     * so there is nothing for an injected instruction in the caller's context to fill. And note the
     * return type: the answer to "please pay" may legitimately be
     * {@link ServerOutcome#APPROVAL_REQUIRED} — a receipt for a decision rather than a payment.
     */
    @McpTool(name = "issueRefund",
            title = "Issue a refund for an order",
            description = """
                    Attempt to refund one order. The amount is taken from the order record and cannot be \
                    specified. Server-side policy decides the outcome: APPLIED when an automatic rule \
                    fully authorises it, APPROVAL_REQUIRED when a human must decide (nothing is paid, \
                    and this server does not accept approvals from tool callers), DECLINED when a rule \
                    forbids it, REPLAYED when the same refund was already issued, REFUSED otherwise.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Issue a refund",
                    readOnlyHint = false,
                    // Honest: money moves and cannot be recalled after settlement.
                    destructiveHint = true,
                    // Honest: repeated calls are deduplicated by a server-derived idempotency key.
                    idempotentHint = true,
                    openWorldHint = true))
    public RefundOutcome issueRefund(
            @McpToolParam(description = "Order identifier such as A-1187", required = true)
            String orderId) {
        // The caller identity would come from the transport's authenticated principal in a real
        // deployment. It is used for rate limiting and the audit trail, never for authorisation.
        return refundDesk.issueRefund(orderId, "mcp-client");
    }

    @McpTool(name = "getRefundStatus",
            title = "Check whether an order has been refunded",
            description = "Return the provider receipt id for an order if a refund has been issued, or an empty result.",
            annotations = @McpTool.McpAnnotations(
                    title = "Refund status",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public RefundOutcome getRefundStatus(
            @McpToolParam(description = "Order identifier such as A-1187", required = true)
            String orderId) {
        long amount = refundDesk.findOrder(orderId).map(OrderSummary::totalMinor).orElse(0L);
        return refundDesk.receiptFor(orderId)
                .map(receipt -> new RefundOutcome(ServerOutcome.REPLAYED,
                        "A refund has been issued for this order.", null, receipt, amount, Instant.now()))
                .orElseGet(() -> new RefundOutcome(ServerOutcome.DECLINED,
                        "No refund has been issued for this order.", null, null, amount, Instant.now()));
    }

    // Deliberately absent: an "approveRefund" tool.
    //
    // Separation of duty. The caller that requests an irreversible action must not be able to
    // authorise it, so approval lives on the admin HTTP surface (ApprovalAdminController) and is
    // never published to MCP. Adding it here would make every control above decorative.
}
