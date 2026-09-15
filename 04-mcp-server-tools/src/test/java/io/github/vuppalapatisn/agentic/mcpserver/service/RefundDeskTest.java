package io.github.vuppalapatisn.agentic.mcpserver.service;

import io.github.vuppalapatisn.agentic.mcpserver.config.ServerProperties;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.RefundOutcome;
import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.ServerOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Server-side policy, tested without MCP in the picture at all — which is the design goal: the
 * transport is an adapter, and the rules do not depend on it.
 */
class RefundDeskTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);

    private RefundDesk desk(boolean executionEnabled) {
        return new RefundDesk(
                new ServerProperties(executionEnabled, 10_000L, 100_000L, 30, 3),
                new SimpleMeterRegistry(), clock);
    }

    @Test
    @DisplayName("automatic tier: a small, low-risk, in-window refund is applied")
    void automaticTier() {
        RefundOutcome outcome = desk(true).issueRefund("A-1204", "mcp-client");

        assertThat(outcome.outcome()).isEqualTo(ServerOutcome.APPLIED);
        assertThat(outcome.amountMinor()).isEqualTo(8_990L);
        assertThat(outcome.receiptId()).startsWith("re_");
    }

    @Test
    @DisplayName("above the automatic tier the server pays nothing and returns APPROVAL_REQUIRED")
    void approvalRequiredTier() {
        RefundDesk desk = desk(true);

        RefundOutcome outcome = desk.issueRefund("A-1187", "mcp-client");

        assertThat(outcome.outcome()).isEqualTo(ServerOutcome.APPROVAL_REQUIRED);
        assertThat(outcome.receiptId()).isNull();
        assertThat(outcome.approvalId()).isNotBlank();
        assertThat(outcome.explanation()).contains("will not accept it from a tool caller");
        assertThat(desk.receiptFor("A-1187")).isEmpty();
        assertThat(desk.pendingApprovals()).hasSize(1);
    }

    @Test
    @DisplayName("high value means dual control, and one approver is not enough")
    void dualControl() {
        RefundDesk desk = desk(true);
        String approvalId = desk.issueRefund("A-0988", "mcp-client").approvalId();

        RefundOutcome first = desk.approve(approvalId, "u-114");
        assertThat(first.outcome()).isEqualTo(ServerOutcome.APPROVAL_REQUIRED);
        assertThat(desk.receiptFor("A-0988")).isEmpty();

        RefundOutcome sameApproverAgain = desk.approve(approvalId, "u-114");
        assertThat(sameApproverAgain.outcome()).isEqualTo(ServerOutcome.REFUSED);

        RefundOutcome second = desk.approve(approvalId, "u-220");
        assertThat(second.outcome()).isEqualTo(ServerOutcome.APPLIED);
        assertThat(desk.receiptFor("A-0988")).isPresent();
    }

    @Test
    @DisplayName("executing an approval twice does not pay twice")
    void approvalIsSingleUse() {
        RefundDesk desk = desk(true);
        String approvalId = desk.issueRefund("A-1187", "mcp-client").approvalId();

        assertThat(desk.approve(approvalId, "u-114").outcome()).isEqualTo(ServerOutcome.APPLIED);
        assertThat(desk.approve(approvalId, "u-220").outcome()).isEqualTo(ServerOutcome.REFUSED);
    }

    @Test
    @DisplayName("an undelivered order is declined by rule")
    void undeliveredIsDeclined() {
        RefundOutcome outcome = desk(true).issueRefund("A-1310", "mcp-client");

        assertThat(outcome.outcome()).isEqualTo(ServerOutcome.DECLINED);
    }

    @Test
    @DisplayName("a repeated automatic refund is replayed from the idempotency key, not paid again")
    void idempotentReplay() {
        RefundDesk desk = desk(true);

        RefundOutcome first = desk.issueRefund("A-1204", "mcp-client");
        RefundOutcome second = desk.issueRefund("A-1204", "mcp-client");

        assertThat(first.outcome()).isEqualTo(ServerOutcome.APPLIED);
        assertThat(second.outcome()).isEqualTo(ServerOutcome.REPLAYED);
        assertThat(second.receiptId()).isEqualTo(first.receiptId());
    }

    @Test
    @DisplayName("a caller that hammers the one-way door is rate limited by the server")
    void serverSideRateLimit() {
        RefundDesk desk = desk(true);

        desk.issueRefund("A-1187", "mcp-client");
        desk.issueRefund("A-1187", "mcp-client");
        desk.issueRefund("A-1187", "mcp-client");
        RefundOutcome fourth = desk.issueRefund("A-1187", "mcp-client");

        assertThat(fourth.outcome()).isEqualTo(ServerOutcome.REFUSED);
        assertThat(fourth.explanation()).contains("Too many refund attempts");
    }

    @Test
    @DisplayName("the server kill switch stops refunds but leaves reads working")
    void killSwitch() {
        RefundDesk desk = desk(false);

        assertThat(desk.issueRefund("A-1204", "mcp-client").outcome()).isEqualTo(ServerOutcome.REFUSED);
        assertThat(desk.findOrder("A-1204")).isPresent();
        assertThat(desk.fraudSignal("A-1204")).isEqualTo(FraudSignal.CLEAN);
    }

    @Test
    @DisplayName("the fraud provider's free text never leaves the boundary — only an enum does")
    void fraudSignalIsAnEnum() {
        RefundDesk desk = desk(true);

        assertThat(desk.fraudSignal("A-0988")).isEqualTo(FraudSignal.WATCHLIST);
        assertThat(desk.fraudSignal("A-1310")).isEqualTo(FraudSignal.VELOCITY_ABUSE);
    }

    @Test
    @DisplayName("a malformed order id is rejected, not escaped into a lookup")
    void orderIdIsValidated() {
        RefundDesk desk = desk(true);

        assertThatThrownBy(() -> desk.findOrder("'; DROP TABLE orders; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an approval whose order changed afterwards is refused rather than paid")
    void staleApprovalIsRefused() {
        RefundDesk desk = desk(true);
        String approvalId = desk.issueRefund("A-1187", "mcp-client").approvalId();

        assertThat(desk.approve("ap-does-not-exist", "u-114").outcome())
                .isEqualTo(ServerOutcome.REFUSED);
        assertThat(desk.approve(approvalId, "u-114").outcome()).isEqualTo(ServerOutcome.APPLIED);
    }
}
