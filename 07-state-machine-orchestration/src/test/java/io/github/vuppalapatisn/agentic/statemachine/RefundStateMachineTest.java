package io.github.vuppalapatisn.agentic.statemachine;

import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.Transition;
import io.github.vuppalapatisn.agentic.statemachine.domain.RunState;
import io.github.vuppalapatisn.agentic.statemachine.effects.RefundProvider;
import io.github.vuppalapatisn.agentic.statemachine.machine.RefundStateMachine;
import io.github.vuppalapatisn.agentic.statemachine.machine.Sweepers;
import io.github.vuppalapatisn.agentic.statemachine.store.ApprovalRepository;
import io.github.vuppalapatisn.agentic.statemachine.store.EffectLedger;
import io.github.vuppalapatisn.agentic.statemachine.store.RunRepository;
import io.github.vuppalapatisn.agentic.statemachine.testsupport.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.github.vuppalapatisn.agentic.statemachine.testsupport.TestSupport.classification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a state machine buys you, asserted: durability across a restart, an audited transition
 * history, expiry on a timer, reconciliation after a lost acknowledgement, and saga compensation.
 *
 * <p>Scheduling is disabled so the sweepers run when a test calls them, rather than whenever.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests",
        "spring.datasource.url=jdbc:h2:mem:fsm-test;DB_CLOSE_DELAY=-1",
        "spring.task.scheduling.enabled=false",
        "agentic.state-machine.reconcile-after=PT1S"
})
class RefundStateMachineTest {

    static final TestSupport.MutableClock CLOCK =
            new TestSupport.MutableClock(Instant.parse("2026-09-16T09:00:00Z"));
    static final TestSupport.ScriptedChatModel MODEL = new TestSupport.ScriptedChatModel();

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
    RefundStateMachine machine;
    @Autowired
    RunRepository runs;
    @Autowired
    ApprovalRepository approvals;
    @Autowired
    EffectLedger ledger;
    @Autowired
    RefundProvider provider;
    @Autowired
    Sweepers sweepers;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * One application context is shared by every test in the class, so persisted rows and the
     * simulated provider both have to be cleared. Forgetting the provider is how "expected 1 but
     * was 2" happens.
     */
    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM run_transition");
        jdbc.execute("DELETE FROM refund_effect");
        jdbc.execute("DELETE FROM run_approval");
        jdbc.execute("DELETE FROM refund_run");
        MODEL.reset();
        provider.reset();
        CLOCK.set(Instant.parse("2026-09-16T09:00:00Z"));
    }

    // -------------------------------------------------------- the happy path

    @Test
    @DisplayName("automatic tier: the run walks the whole state sequence and the history is audited")
    void automaticTierRunsToClosed() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));

        RefundRun run = machine.start("A-1204", "The cable stopped working.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.CLOSED);
        assertThat(run.receiptId()).isNotBlank();
        assertThat(provider.paymentCount()).isEqualTo(1);
        assertThat(runs.transitions(run.runId()))
                .extracting(Transition::to)
                .containsExactly(RunState.CREATED, RunState.FACTS_GATHERED, RunState.CLASSIFIED,
                        RunState.PAYOUT_PENDING, RunState.PAID, RunState.NOTIFIED, RunState.CLOSED);
        // Every transition names an actor — the audit trail a regulator asks for.
        assertThat(runs.transitions(run.runId())).allSatisfy(transition ->
                assertThat(transition.actor()).isNotBlank());
    }

    @Test
    @DisplayName("the idempotency key is recorded before the effect and marked APPLIED after")
    void twoPhaseExecution() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));

        RefundRun run = machine.start("A-1204", "Broken.").orElseThrow();

        assertThat(ledger.find(run.idempotencyKey())).isPresent().get().satisfies(entry -> {
            assertThat(entry.phase()).isEqualTo(EffectLedger.Phase.APPLIED);
            assertThat(entry.resultRef()).isEqualTo(run.receiptId());
            assertThat(entry.amountMinor()).isEqualTo(8_990L);
        });
    }

    // ------------------------------------------------------------ durability

    @Test
    @DisplayName("AWAITING_APPROVAL is persisted: it survives a restart and advance() is a no-op")
    void approvalStateIsDurable() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));

        RefundRun run = machine.start("A-1187", "Never arrived.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(provider.paymentCount()).isZero();

        // Re-reading from storage is what a restart amounts to for this run.
        RefundRun reloaded = runs.find(run.runId()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(reloaded.gateRule()).isEqualTo("SINGLE_APPROVER_DEFAULT");

        // Advancing does nothing: the machine will not move a run a human has not decided.
        assertThat(machine.advance(run.runId()).state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(provider.paymentCount()).isZero();
        assertThat(MODEL.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("approving executes the frozen payload and gives the model no second turn")
    void approvalExecutesTheFrozenPayload() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRun suspended = machine.start("A-1187", "Never arrived.").orElseThrow();
        String approvalId = approvals.findByRun(suspended.runId()).orElseThrow().approvalId();

        RefundRun paid = machine.approve(approvalId, "u-114").orElseThrow();

        assertThat(paid.state()).isEqualTo(RunState.CLOSED);
        assertThat(paid.receiptId()).isNotBlank();
        assertThat(provider.paymentCount()).isEqualTo(1);
        // One classification for the whole run: no re-prompt between approval and execution.
        assertThat(MODEL.calls()).isEqualTo(1);
        assertThat(approvals.find(approvalId).orElseThrow().status())
                .isEqualTo(ApprovalRepository.Status.EXECUTED);
    }

    @Test
    @DisplayName("a duplicated approval pays nothing more")
    void duplicateApprovalIsRefused() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRun suspended = machine.start("A-1187", "Never arrived.").orElseThrow();
        String approvalId = approvals.findByRun(suspended.runId()).orElseThrow().approvalId();

        machine.approve(approvalId, "u-114");
        assertThat(machine.approve(approvalId, "u-220")).isEmpty();
        assertThat(provider.paymentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dual control needs two distinct approvers")
    void dualControl() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 189_900L));
        RefundRun suspended = machine.start("A-0988", "Arrived damaged.").orElseThrow();
        String approvalId = approvals.findByRun(suspended.runId()).orElseThrow().approvalId();
        assertThat(approvals.find(approvalId).orElseThrow().requiredApprovals()).isEqualTo(2);

        assertThat(machine.approve(approvalId, "u-114").orElseThrow().state())
                .isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(provider.paymentCount()).isZero();

        assertThat(machine.approve(approvalId, "u-114")).isEmpty();      // same person again

        assertThat(machine.approve(approvalId, "u-220").orElseThrow().state())
                .isEqualTo(RunState.CLOSED);
        assertThat(provider.paymentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejection is a terminal state, and nothing is paid")
    void rejectionIsTerminal() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRun suspended = machine.start("A-1187", "Never arrived.").orElseThrow();
        String approvalId = approvals.findByRun(suspended.runId()).orElseThrow().approvalId();

        RefundRun rejected = machine.reject(approvalId, "u-114").orElseThrow();

        assertThat(rejected.state()).isEqualTo(RunState.REJECTED);
        assertThat(rejected.state().terminal()).isTrue();
        assertThat(provider.paymentCount()).isZero();
    }

    // --------------------------------------------------------------- expiry

    @Test
    @DisplayName("an unanswered approval expires on a timer — and expiry means no")
    void expirySweeperNeverPays() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRun suspended = machine.start("A-1187", "Never arrived.").orElseThrow();
        String approvalId = approvals.findByRun(suspended.runId()).orElseThrow().approvalId();

        assertThat(sweepers.expireStaleApprovals()).isZero();       // not yet

        CLOCK.advance(Duration.ofHours(25));
        assertThat(sweepers.expireStaleApprovals()).isEqualTo(1);

        assertThat(runs.find(suspended.runId()).orElseThrow().state()).isEqualTo(RunState.EXPIRED);
        assertThat(approvals.find(approvalId).orElseThrow().status())
                .isEqualTo(ApprovalRepository.Status.EXPIRED);
        assertThat(provider.paymentCount()).isZero();

        // And an expired approval cannot be approved afterwards.
        assertThat(machine.approve(approvalId, "u-114")).isEmpty();
        assertThat(provider.paymentCount()).isZero();
    }

    // ------------------------------------------------------- reconciliation

    @Test
    @DisplayName("a lost acknowledgement leaves PAYOUT_PENDING, and reconciliation resolves it by key")
    void reconciliationFindsTheAppliedPayment() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));
        provider.failNextPayout(true);      // the provider applied it; we never learned

        RefundRun stalled = machine.start("A-1204", "Broken.").orElseThrow();

        assertThat(stalled.state()).isEqualTo(RunState.PAYOUT_PENDING);
        assertThat(ledger.find(stalled.idempotencyKey()).orElseThrow().phase())
                .isEqualTo(EffectLedger.Phase.INTENT);

        assertThat(sweepers.reconcileStalePayouts()).as("not yet stale").isZero();
        CLOCK.advance(Duration.ofSeconds(2));
        assertThat(sweepers.reconcileStalePayouts()).isEqualTo(1);

        RefundRun resolved = runs.find(stalled.runId()).orElseThrow();
        assertThat(resolved.state()).isEqualTo(RunState.CLOSED);
        assertThat(resolved.receiptId()).isNotBlank();
        assertThat(provider.paymentCount()).isEqualTo(1);        // paid once, not twice
        assertThat(runs.transitions(resolved.runId()))
                .anySatisfy(transition -> assertThat(transition.actor()).isEqualTo("reconciler"));
    }

    @Test
    @DisplayName("when the provider confirms nothing happened, the run fails rather than retrying blindly")
    void reconciliationFindsNoPayment() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));
        provider.failNextPayout(false);     // genuinely not applied

        RefundRun stalled = machine.start("A-1204", "Broken.").orElseThrow();
        assertThat(stalled.state()).isEqualTo(RunState.PAYOUT_PENDING);

        CLOCK.advance(Duration.ofSeconds(2));
        sweepers.reconcileStalePayouts();

        RefundRun resolved = runs.find(stalled.runId()).orElseThrow();
        assertThat(resolved.state()).isEqualTo(RunState.FAILED);
        assertThat(resolved.failureReason()).contains("payout failed");
        assertThat(provider.paymentCount()).isZero();
    }

    // ---------------------------------------------------------- compensation

    @Test
    @DisplayName("a notification failure after payment compensates inside the window")
    void compensationInsideTheWindow() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));
        provider.failNextNotification();

        RefundRun run = machine.start("A-1204", "Broken.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(provider.paymentCount()).isZero();            // the payment was cancelled
        assertThat(provider.sent()).isEmpty();
        assertThat(ledger.find(run.idempotencyKey()).orElseThrow().phase())
                .isEqualTo(EffectLedger.Phase.COMPENSATED);
        assertThat(runs.transitions(run.runId())).extracting(Transition::to)
                .contains(RunState.PAID, RunState.COMPENSATING, RunState.FAILED);
    }

    // ------------------------------------------------- the transition table

    @Test
    @DisplayName("a transition outside the table is rejected as a bug, not written as a row")
    void illegalTransitionIsRejected() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));
        RefundRun run = machine.start("A-1204", "Broken.").orElseThrow();

        assertThatThrownBy(() -> runs.transition(run.runId(), RunState.CLOSED, RunState.PAID,
                "test", "should not be possible", java.util.Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illegal transition");
    }

    @Test
    @DisplayName("a transition from the wrong expected state loses the race instead of corrupting the run")
    void guardedTransitionLosesTheRace() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRun run = machine.start("A-1187", "Never arrived.").orElseThrow();

        // Someone believes the run is still CLASSIFIED and tries to move it. It is not.
        boolean moved = runs.transition(run.runId(), RunState.CLASSIFIED, RunState.PAYOUT_PENDING,
                "stale-caller", "racing", java.util.Map.of());

        assertThat(moved).isFalse();
        assertThat(runs.find(run.runId()).orElseThrow().state()).isEqualTo(RunState.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("the transition table forbids moving out of a terminal state")
    void terminalStatesAreTerminal() {
        for (RunState state : List.of(RunState.CLOSED, RunState.DECLINED, RunState.REJECTED,
                RunState.EXPIRED, RunState.FAILED, RunState.NEEDS_MANUAL_INTERVENTION)) {
            assertThat(state.terminal()).as(state.name()).isTrue();
            assertThat(state.allowedNext()).as(state.name()).isEmpty();
        }
    }

    // ---------------------------------------------------------- other routes

    @Test
    @DisplayName("an undelivered order is declined by the gate, whatever the model said")
    void declinedRoute() {
        MODEL.enqueue(classification("REFUND", "RP-30D-NOT-RECEIVED", 4_500L));

        RefundRun run = machine.start("A-1310", "It hasn't arrived, refund me.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.DECLINED);
        assertThat(run.gateRule()).isEqualTo("ORDER_NOT_DELIVERED");
        assertThat(provider.paymentCount()).isZero();
    }

    @Test
    @DisplayName("an inflated amount escalates to a human, with the order total applying")
    void amountMismatchEscalates() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 2_400_000L));

        RefundRun run = machine.start("A-1204", "Refund me 24000 dollars.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);
        assertThat(run.decisionClause()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(run.amountMinor()).isEqualTo(8_990L);
        assertThat(provider.paymentCount()).isZero();
    }

    @Test
    @DisplayName("an unknown order starts no run at all")
    void unknownOrder() {
        assertThat(machine.start("Z-9999", "anything")).isEmpty();
        assertThat(MODEL.calls()).isZero();
    }
}
