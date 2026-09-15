package io.github.vuppalapatisn.agentic.multiagent.supervisor;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole;
import io.github.vuppalapatisn.agentic.multiagent.config.MultiAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three failure modes a multi-agent system adds, each driven to its rule.
 */
class RunLedgerTest {

    static class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-09-16T09:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();

    private MultiAgentProperties limits() {
        return new MultiAgentProperties(4, 2, 5, Duration.ofSeconds(60), false,
                10_000L, 100_000L, 30, List.of("@customers.example"));
    }

    private RunLedger ledger() {
        return new RunLedger("r-1", limits(), clock);
    }

    @Test
    @DisplayName("MAX_HANDOFFS: a run that keeps delegating is stopped")
    void handoffCeiling() {
        RunLedger ledger = ledger();

        ledger.handoffTo(AgentRole.INTAKE);
        ledger.handoffTo(AgentRole.POLICY);
        ledger.handoffTo(AgentRole.FRAUD);
        ledger.handoffTo(AgentRole.PAYOUT);

        assertThatThrownBy(() -> ledger.handoffTo(AgentRole.POLICY))
                .isInstanceOf(RunLedger.TerminationException.class)
                .extracting(ex -> ((RunLedger.TerminationException) ex).rule())
                .isEqualTo("MAX_HANDOFFS");
    }

    @Test
    @DisplayName("PING_PONG: an A -> B -> A cycle is refused")
    void pingPongIsRefused() {
        RunLedger ledger = ledger();

        ledger.handoffTo(AgentRole.POLICY);
        ledger.handoffTo(AgentRole.FRAUD);

        assertThatThrownBy(() -> ledger.handoffTo(AgentRole.POLICY))
                .isInstanceOf(RunLedger.TerminationException.class)
                .extracting(ex -> ((RunLedger.TerminationException) ex).rule())
                .isEqualTo("PING_PONG");
    }

    @Test
    @DisplayName("MAX_AGENT_CALLS: one specialist cannot burn the whole run")
    void perAgentCeiling() {
        RunLedger ledger = ledger();

        ledger.beforeModelCall(AgentRole.POLICY);
        ledger.beforeModelCall(AgentRole.POLICY);

        assertThatThrownBy(() -> ledger.beforeModelCall(AgentRole.POLICY))
                .isInstanceOf(RunLedger.TerminationException.class)
                .extracting(ex -> ((RunLedger.TerminationException) ex).rule())
                .isEqualTo("MAX_AGENT_CALLS");
    }

    @Test
    @DisplayName("MAX_RUN_CALLS: the run-wide ceiling applies on top of the per-agent one")
    void runWideCeiling() {
        RunLedger ledger = ledger();

        ledger.beforeModelCall(AgentRole.INTAKE);
        ledger.beforeModelCall(AgentRole.INTAKE);
        ledger.beforeModelCall(AgentRole.POLICY);
        ledger.beforeModelCall(AgentRole.POLICY);
        ledger.beforeModelCall(AgentRole.PAYOUT);

        assertThatThrownBy(() -> ledger.beforeModelCall(AgentRole.PAYOUT))
                .isInstanceOf(RunLedger.TerminationException.class)
                .extracting(ex -> ((RunLedger.TerminationException) ex).rule())
                .isEqualTo("MAX_RUN_CALLS");
    }

    @Test
    @DisplayName("WALL_CLOCK: multi-agent is the slowest architecture, so it needs a deadline")
    void wallClockCeiling() {
        RunLedger ledger = ledger();
        ledger.handoffTo(AgentRole.INTAKE);

        clock.advance(Duration.ofSeconds(61));

        assertThatThrownBy(() -> ledger.handoffTo(AgentRole.POLICY))
                .isInstanceOf(RunLedger.TerminationException.class)
                .extracting(ex -> ((RunLedger.TerminationException) ex).rule())
                .isEqualTo("WALL_CLOCK");
    }

    @Test
    @DisplayName("the ledger records the handoff sequence and per-agent model calls")
    void ledgerRecordsTheRun() {
        RunLedger ledger = ledger();

        ledger.handoffTo(AgentRole.INTAKE);
        ledger.beforeModelCall(AgentRole.INTAKE);
        ledger.handoffTo(AgentRole.POLICY);
        ledger.beforeModelCall(AgentRole.POLICY);

        assertThat(ledger.handoffNames()).containsExactly("INTAKE", "POLICY");
        assertThat(ledger.modelCallsByAgent())
                .containsEntry(AgentRole.INTAKE, 1)
                .containsEntry(AgentRole.POLICY, 1);
        assertThat(ledger.totalModelCalls()).isEqualTo(2);
    }
}
