package io.github.vuppalapatisn.agentic.foundation.web;

import io.github.vuppalapatisn.agentic.foundation.domain.RefundDecision;

/**
 * Response of the read-only classification endpoint.
 *
 * @param runId          correlation id — return it so a caller can quote it in a support ticket
 * @param decision       the model's classification, reconciled against trusted order data
 * @param authoritativeAmountMinor the amount that <b>would</b> be refunded, read from the order
 * @param advisoryOnly   always {@code true} in this project: nothing here moves money
 */
public record ClassifyResponse(
        String runId,
        RefundDecision decision,
        long authoritativeAmountMinor,
        boolean advisoryOnly,
        String draftReply) {
}
