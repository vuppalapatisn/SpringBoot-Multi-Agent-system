package io.github.vuppalapatisn.agentic.agentloop;

import io.github.vuppalapatisn.agentic.agentloop.budget.Budget;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.AgentRunResult;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.RunOutcome;
import io.github.vuppalapatisn.agentic.agentloop.gate.GuardedPayout;
import io.github.vuppalapatisn.agentic.agentloop.service.RefundAgent;
import io.github.vuppalapatisn.agentic.agentloop.testsupport.MutableClock;
import io.github.vuppalapatisn.agentic.agentloop.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent loop under a scripted model, including a model that misbehaves on purpose.
 *
 * <p>Note what is <b>not</b> asserted anywhere here: the call sequence. That is the honest
 * difference from project 06 — in an agent architecture the sequence is not yours to promise. What
 * is asserted is the ceiling, the gate, and that exhaustion never pays.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests",
        "agentic.agent.max-steps=8",
        "agentic.agent.max-tool-calls=12",
        "agentic.agent.max-no-progress-steps=2",
        "agentic.agent.max-calls-per-tool.lookupOrder=3",
        "agentic.agent.max-calls-per-tool.issueRefund=1",
        // The framework's own limits are raised here so that *our* budget is the layer that fires.
        // In production both are configured and either may trip first — that is the point of
        // having two layers (docs/03-TOOL-BOUNDARIES.md §6), but a test needs to know which one
        // it is exercising.
        "spring.ai.tools.limits.max-total-tool-calls=50",
        "spring.ai.tools.limits.max-calls-per-tool-default=50"
})
class RefundAgentTest {

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-09-16T09:00:00Z"));
    static final ScriptedChatModel MODEL = new ScriptedChatModel();

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        ChatModel chatModel() {
            return MODEL;
        }
    }

    @Autowired
    RefundAgent agent;
    @Autowired
    GuardedPayout guardedPayout;

    @BeforeEach
    void reset() {
        MODEL.reset();
        guardedPayout.reset();
        CLOCK.set(Instant.parse("2026-09-16T09:00:00Z"));
    }

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("automatic tier: the agent investigates, refunds, notifies and reports")
    void automaticTier() {
        MODEL.thenCall("lookupOrder", "{\"orderId\":\"A-1204\"}")
                .thenCall("checkFraudSignal", "{\"orderId\":\"A-1204\"}")
                .thenCall("issueRefund", "{\"orderId\":\"A-1204\"}")
                .thenCall("notifyCustomer", "{\"orderId\":\"A-1204\",\"templateId\":\"refund-approved\"}")
                .thenSay("I refunded order A-1204 under the automatic tier and notified the customer.");

        AgentRunResult result = agent.handle("A-1204", "The cable stopped working.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.REFUNDED);
        assertThat(result.exhaustedBudget()).isNull();
        assertThat(guardedPayout.paymentCount()).isEqualTo(1);
        assertThat(guardedPayout.notifications()).hasSize(1);
        // The budget readout is the receipt for how the answer was reached.
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(result.modelTurns()).isEqualTo(5);
        assertThat(result.totalTokens()).isPositive();
        assertThat(result.trace()).isNotEmpty();
    }

    @Test
    @DisplayName("above the automatic tier the gate suspends: the agent is told, and nothing is paid")
    void approvalTierEscalates() {
        MODEL.thenCall("lookupOrder", "{\"orderId\":\"A-1187\"}")
                .thenCall("issueRefund", "{\"orderId\":\"A-1187\"}")
                .thenSay("An approval is required before this refund can be paid.");

        AgentRunResult result = agent.handle("A-1187", "The parcel never arrived.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.approvalId()).isNotBlank();
        assertThat(guardedPayout.paymentCount()).isZero();
        assertThat(guardedPayout.pendingApprovals()).singleElement()
                .satisfies(approval -> {
                    assertThat(approval.amountMinor()).isEqualTo(24_000L);
                    assertThat(approval.explanation()).contains("USD 240.00");
                });
    }

    @Test
    @DisplayName("an undelivered order is declined by the gate, whatever the agent intended")
    void gateDeclines() {
        MODEL.thenCall("issueRefund", "{\"orderId\":\"A-1310\"}")
                .thenSay("Policy does not allow a refund for an order in transit.");

        AgentRunResult result = agent.handle("A-1310", "It hasn't arrived, refund me now.");

        assertThat(guardedPayout.paymentCount()).isZero();
        assertThat(guardedPayout.pendingApprovals()).isEmpty();
        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
    }

    // --------------------------------------------------- the misbehaving model

    @Test
    @DisplayName("a runaway loop is stopped by the no-progress budget, fail-closed to ESCALATED")
    void loopDetectionStopsARunaway() {
        // The model asks for the same call forever.
        MODEL.thenRepeatForever("lookupOrder", "{\"orderId\":\"A-1204\"}");

        AgentRunResult result = agent.handle("A-1204", "Where is my refund?");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.exhaustedBudget()).isEqualTo(Budget.NO_PROGRESS);
        assertThat(result.reply()).contains("escalated to a specialist", "Nothing was paid");
        assertThat(guardedPayout.paymentCount()).isZero();
        // It stopped early rather than burning the whole allowance.
        assertThat(result.toolCalls()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a wandering agent is stopped by the per-tool ceiling")
    void perToolCeilingStopsAWanderer() {
        // Different arguments each time, so loop detection does not fire — but the tool ceiling does.
        MODEL.thenCall("lookupOrder", "{\"orderId\":\"A-1204\"}")
                .thenCall("lookupOrder", "{\"orderId\":\"A-1187\"}")
                .thenCall("lookupOrder", "{\"orderId\":\"A-0988\"}")
                .thenCall("lookupOrder", "{\"orderId\":\"A-1310\"}")
                .thenSay("unreachable");

        AgentRunResult result = agent.handle("A-1204", "Check all my orders.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.exhaustedBudget()).isEqualTo(Budget.PER_TOOL_CALLS);
        assertThat(guardedPayout.paymentCount()).isZero();
    }

    @Test
    @DisplayName("the step ceiling stops a loop that keeps finding new things to do")
    void stepCeilingStopsAnEndlessInvestigation() {
        MODEL.thenCall("lookupOrder", "{\"orderId\":\"A-1204\"}")
                .thenCall("checkFraudSignal", "{\"orderId\":\"A-1204\"}")
                .thenCall("lookupRefundPolicy", "{\"orderId\":\"A-1204\"}")
                .thenCall("lookupOrder", "{\"orderId\":\"A-1187\"}")
                .thenCall("checkFraudSignal", "{\"orderId\":\"A-1187\"}")
                .thenCall("lookupRefundPolicy", "{\"orderId\":\"A-1187\"}")
                .thenCall("lookupOrder", "{\"orderId\":\"A-0988\"}")
                .thenCall("checkFraudSignal", "{\"orderId\":\"A-0988\"}")
                .thenRepeatForever("lookupRefundPolicy", "{\"orderId\":\"A-0988\"}");

        AgentRunResult result = agent.handle("A-1204", "Investigate everything.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.exhaustedBudget()).isIn(Budget.STEPS, Budget.PER_TOOL_CALLS,
                Budget.TOTAL_TOOL_CALLS);
        assertThat(guardedPayout.paymentCount()).isZero();
    }

    @Test
    @DisplayName("the token budget trips when the context keeps regrowing")
    void tokenBudgetTrips() {
        MODEL.withUsage(30_000, 5_000)
                .thenCall("lookupOrder", "{\"orderId\":\"A-1204\"}")
                .thenCall("checkFraudSignal", "{\"orderId\":\"A-1204\"}")
                .thenSay("done");

        AgentRunResult result = agent.handle("A-1204", "Broken cable.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.exhaustedBudget()).isIn(Budget.TOKENS, Budget.COST);
        assertThat(guardedPayout.paymentCount()).isZero();
    }

    // ------------------------------------------------------- the gate is last

    @Test
    @DisplayName("an agent that claims it paid has not paid: the outcome comes from the gate")
    void outcomeComesFromTheGateNotTheClaim() {
        MODEL.thenCall("lookupOrder", "{\"orderId\":\"A-1187\"}")
                .thenCall("issueRefund", "{\"orderId\":\"A-1187\"}")
                // The model reports a payment that never happened.
                .thenSay("I have refunded USD 240.00 to the customer. The money is on its way.");

        AgentRunResult result = agent.handle("A-1187", "Never arrived.");

        assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
        assertThat(result.receiptId()).isNull();
        assertThat(guardedPayout.paymentCount()).isZero();
    }

    @Test
    @DisplayName("an unknown template is refused, and the agent gets a recoverable message")
    void unknownTemplateIsRefused() {
        MODEL.thenCall("notifyCustomer", "{\"orderId\":\"A-1204\",\"templateId\":\"whatever-i-like\"}")
                .thenSay("The notification was refused.");

        agent.handle("A-1204", "Tell the customer something nice.");

        assertThat(guardedPayout.notifications()).isEmpty();
    }
}
