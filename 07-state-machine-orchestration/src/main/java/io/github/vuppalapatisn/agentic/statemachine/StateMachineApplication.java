package io.github.vuppalapatisn.agentic.statemachine;

import io.github.vuppalapatisn.agentic.statemachine.config.StateMachineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 07 — the Refund Desk as a persisted state machine.
 *
 * <p>Same problem as projects 06, 08 and 09. What this architecture adds: the run has somewhere to
 * <b>be</b> while a human decides, every transition is audited, approvals expire on a timer, and a
 * crash between intent and effect is reconciled rather than guessed at. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(StateMachineProperties.class)
public class StateMachineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StateMachineApplication.class, args);
    }
}
