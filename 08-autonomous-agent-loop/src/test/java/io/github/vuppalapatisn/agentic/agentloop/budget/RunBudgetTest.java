package io.github.vuppalapatisn.agentic.agentloop.budget;

import io.github.vuppalapatisn.agentic.agentloop.config.AgentProperties;
import io.github.vuppalapatisn.agentic.agentloop.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every budget, driven to exhaustion. This is the Phase 7 requirement from the checklist:
 * "there is a test that drives each budget to exhaustion and asserts the terminal state."
 */
class RunBudgetTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T09:00:00Z"));

    private AgentProperties limits() {
        return new AgentProperties(
                3,                                  // maxSteps
                4,                                  // maxToolCalls
                2,                                  // maxCallsPerToolDefault
                Map.of("issueRefund", 1),
                1_000,                              // maxTokens
                10L,                                // maxCostMinor
                Duration.ofSeconds(30),
                2,                                  // maxNoProgressSteps
                300L, 1_500L,
                false, false,
                10_000L, 100_000L, 30,
                List.of("@customers.example"));
    }

    private RunBudget budget() {
        return new RunBudget("r-1", limits(), clock);
    }

    @Test
    @DisplayName("STEPS: the model-turn ceiling stops the loop")
    void stepCeiling() {
        RunBudget budget = budget();

        budget.beginStep();
        budget.beginStep();
        budget.beginStep();

        assertThatThrownBy(budget::beginStep)
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.STEPS);
        assertThat(budget.modelTurns()).isEqualTo(3);
    }

    @Test
    @DisplayName("TOTAL_TOOL_CALLS: the run-wide tool ceiling stops the loop")
    void totalToolCallCeiling() {
        RunBudget budget = budget();

        budget.beforeToolCall("lookupOrder", "lookupOrder(A-1)");
        budget.beforeToolCall("lookupRefundPolicy", "lookupRefundPolicy(A-1)");
        budget.beforeToolCall("checkFraudSignal", "checkFraudSignal(A-1)");
        budget.beforeToolCall("notifyCustomer", "notifyCustomer(A-1,refund-review)");

        assertThatThrownBy(() -> budget.beforeToolCall("lookupOrder", "lookupOrder(A-2)"))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.TOTAL_TOOL_CALLS);
    }

    @Test
    @DisplayName("PER_TOOL_CALLS: a named irreversible tool gets exactly one call")
    void perToolCeiling() {
        RunBudget budget = budget();

        budget.beforeToolCall("issueRefund", "issueRefund(A-1)");

        assertThatThrownBy(() -> budget.beforeToolCall("issueRefund", "issueRefund(A-2)"))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.PER_TOOL_CALLS);
    }

    @Test
    @DisplayName("NO_PROGRESS: the same tool with the same arguments twice ends the run")
    void loopDetection() {
        RunBudget budget = budget();

        budget.beforeToolCall("lookupOrder", "lookupOrder(A-1204)");

        assertThatThrownBy(() -> budget.beforeToolCall("lookupOrder", "lookupOrder(A-1204)"))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.NO_PROGRESS);
    }

    @Test
    @DisplayName("NO_PROGRESS: different arguments are progress, so the streak resets")
    void differentArgumentsAreProgress() {
        RunBudget budget = budget();

        budget.beforeToolCall("lookupOrder", "lookupOrder(A-1204)");
        budget.beforeToolCall("lookupOrder", "lookupOrder(A-1187)");

        assertThat(budget.toolCalls()).isEqualTo(2);
        assertThat(budget.callsPerTool()).containsEntry("lookupOrder", 2);
    }

    @Test
    @DisplayName("TOKENS: context regrows every step, so the token ceiling matters")
    void tokenCeiling() {
        RunBudget budget = budget();

        budget.recordUsage(400, 100);
        assertThat(budget.totalTokens()).isEqualTo(500);

        assertThatThrownBy(() -> budget.recordUsage(400, 200))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.TOKENS);
    }

    @Test
    @DisplayName("COST: the estimate trips before a runaway loop becomes an invoice")
    void costCeiling() {
        AgentProperties expensive = new AgentProperties(
                50, 50, 50, Map.of(), 10_000_000, 1L, Duration.ofSeconds(30), 50,
                1_000_000L, 1_000_000L, false, false, 10_000L, 100_000L, 30,
                List.of("@customers.example"));
        RunBudget budget = new RunBudget("r-2", expensive, clock);

        assertThatThrownBy(() -> budget.recordUsage(1_000_000, 1_000_000))
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.COST);
    }

    @Test
    @DisplayName("WALL_CLOCK: the budget a waiting human actually feels")
    void wallClockCeiling() {
        RunBudget budget = budget();
        budget.beginStep();

        clock.advance(Duration.ofSeconds(31));

        assertThatThrownBy(budget::beginStep)
                .isInstanceOf(BudgetExceededException.class)
                .extracting(ex -> ((BudgetExceededException) ex).budget())
                .isEqualTo(Budget.WALL_CLOCK);
        assertThatThrownBy(() -> budget.beforeToolCall("lookupOrder", "lookupOrder(A-1)"))
                .isInstanceOf(BudgetExceededException.class);
    }

    @Test
    @DisplayName("the trace records every step and tool call, in order")
    void traceIsRecorded() {
        RunBudget budget = budget();

        budget.beginStep();
        budget.beforeToolCall("lookupOrder", "lookupOrder(A-1204)");
        budget.note("GATE", "issueRefund -> APPROVAL_REQUIRED");

        assertThat(budget.steps()).extracting(RunBudget.Step::kind)
                .containsExactly("LLM", "TOOL", "GATE");
        assertThat(budget.summary()).contains("steps=1", "tools=1");
    }
}
