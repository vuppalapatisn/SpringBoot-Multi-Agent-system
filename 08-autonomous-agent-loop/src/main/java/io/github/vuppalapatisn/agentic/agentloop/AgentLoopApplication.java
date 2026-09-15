package io.github.vuppalapatisn.agentic.agentloop;

import io.github.vuppalapatisn.agentic.agentloop.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 08 — the Refund Desk as an autonomous agent loop.
 *
 * <p>Same problem as projects 06, 07 and 09. Here the <b>model</b> decides what happens next, so
 * the interesting code is not the loop but the budgets, the loop detection and the gate the model
 * cannot reach around. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentLoopApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLoopApplication.class, args);
    }
}
