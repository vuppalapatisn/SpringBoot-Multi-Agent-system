package io.github.vuppalapatisn.agentic.workflow;

import io.github.vuppalapatisn.agentic.workflow.config.WorkflowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 06 — the Refund Desk as a deterministic workflow.
 *
 * <p>First of four projects that solve the <b>same</b> problem: 06 workflow, 07 state machine,
 * 08 agent loop, 09 multi-agent. Diff them. See {@code docs/CFG.md} and
 * {@code ../docs/02-ARCHITECTURE-COMPARISON.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}
