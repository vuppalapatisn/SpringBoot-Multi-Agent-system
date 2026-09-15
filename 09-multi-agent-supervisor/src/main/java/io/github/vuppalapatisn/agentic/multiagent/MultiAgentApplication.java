package io.github.vuppalapatisn.agentic.multiagent;

import io.github.vuppalapatisn.agentic.multiagent.config.MultiAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 09 — the Refund Desk as a supervised multi-agent system.
 *
 * <p>Same problem as projects 06, 07 and 08. The value here is not extra intelligence but
 * <b>separation of authority</b>: four specialists, typed handoffs, and exactly one agent that can
 * move money. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(MultiAgentProperties.class)
public class MultiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiAgentApplication.class, args);
    }
}
