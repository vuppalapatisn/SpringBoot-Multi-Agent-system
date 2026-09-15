package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.tools.domain.RefundActionResult;
import io.github.vuppalapatisn.agentic.tools.provider.OrderDirectory;
import io.github.vuppalapatisn.agentic.tools.tools.RefundTools;
import org.springframework.stereotype.Component;

/**
 * Executes an approved effect from its <b>frozen</b> payload.
 *
 * <p>This class exists to guarantee the property that matters most in human-in-the-loop agentic
 * systems: <b>the model gets no turn between approval and execution</b>. Resuming does not re-prompt
 * anything. It rebuilds the exact {@link EffectContext} that was hashed when the approval was
 * created, so {@link ApprovalStore#consume} can verify that what runs is what was approved.
 *
 * <p>It also re-checks the frozen amount against the live order. If an order changed after the
 * approval was raised, that is an integrity problem, not something to resolve by paying anyway.
 */
@Component
public class FrozenEffectRunner {

    private final OrderDirectory orders;
    private final RefundTools refundTools;

    public FrozenEffectRunner(OrderDirectory orders, RefundTools refundTools) {
        this.orders = orders;
        this.refundTools = refundTools;
    }

    public RefundActionResult execute(PendingApproval approval) {
        OrderSummary order = orders.find(approval.businessKey()).orElse(null);
        if (order == null) {
            return RefundActionResult.refused(
                    "order " + approval.businessKey() + " no longer exists; refusing to execute " + approval.id());
        }
        if (order.totalMinor() != approval.amountMinor()) {
            return RefundActionResult.refused(
                    ("order %s total changed from %d to %d after approval %s was raised; "
                            + "refusing to execute a stale approval")
                            .formatted(order.orderId(), approval.amountMinor(), order.totalMinor(), approval.id()));
        }

        EffectContext frozen = new EffectContext(
                approval.runId(),
                approval.toolName(),
                approval.businessKey(),
                approval.amountMinor(),
                approval.payload(),
                approval.id());

        return switch (approval.toolName()) {
            case "issueRefund" -> refundTools.applyRefund(frozen, order);
            default -> RefundActionResult.refused(
                    "no frozen executor registered for tool '" + approval.toolName() + "'");
        };
    }
}
