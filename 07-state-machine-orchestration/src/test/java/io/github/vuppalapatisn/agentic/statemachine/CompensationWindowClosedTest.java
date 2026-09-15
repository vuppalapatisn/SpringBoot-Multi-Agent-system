package io.github.vuppalapatisn.agentic.statemachine;

import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.Transition;
import io.github.vuppalapatisn.agentic.statemachine.domain.RunState;
import io.github.vuppalapatisn.agentic.statemachine.effects.RefundProvider;
import io.github.vuppalapatisn.agentic.statemachine.machine.RefundStateMachine;
import io.github.vuppalapatisn.agentic.statemachine.store.RunRepository;
import io.github.vuppalapatisn.agentic.statemachine.testsupport.TestSupport;
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

import static io.github.vuppalapatisn.agentic.statemachine.testsupport.TestSupport.classification;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case that most designs forget: <b>compensation is required but no longer possible</b>.
 *
 * <p>A settlement delay of {@code PT-1S} means every payment is already settled, so the
 * compensation window is closed by the time the saga reaches it. The run must land in
 * {@link RunState#NEEDS_MANUAL_INTERVENTION} — a distinct state from {@code FAILED}, because this
 * one needs a human and a page, not a retry.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests",
        "spring.datasource.url=jdbc:h2:mem:fsm-window-test;DB_CLOSE_DELAY=-1",
        "spring.task.scheduling.enabled=false",
        "agentic.state-machine.settlement-delay=PT-1S"
})
class CompensationWindowClosedTest {

    static final TestSupport.ScriptedChatModel MODEL = new TestSupport.ScriptedChatModel();

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), java.time.ZoneOffset.UTC);
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
    RefundProvider provider;

    @Test
    @DisplayName("compensation after the window has closed pages a human instead of failing quietly")
    void windowClosedNeedsManualIntervention() {
        MODEL.enqueue(classification("REFUND", "RP-30D-DAMAGED", 8_990L));
        provider.failNextNotification();

        RefundRun run = machine.start("A-1204", "Broken cable.").orElseThrow();

        assertThat(run.state()).isEqualTo(RunState.NEEDS_MANUAL_INTERVENTION);
        assertThat(run.failureReason()).contains("compensation window closed");
        // The money is still out: that is the truth the state has to tell.
        assertThat(provider.paymentCount()).isEqualTo(1);
        assertThat(provider.sent()).isEmpty();
        assertThat(runs.transitions(run.runId())).extracting(Transition::to)
                .contains(RunState.PAID, RunState.COMPENSATING, RunState.NEEDS_MANUAL_INTERVENTION);
    }
}
