package io.github.vuppalapatisn.agentic.tools;

import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 02 — tool calling with guardrails.
 *
 * <p>This is the first project where the model can change the world, so it is the first with a
 * non-empty irreversible-action catalogue. Read {@code docs/CFG.md} before changing anything.
 */
@SpringBootApplication
@EnableConfigurationProperties(GuardrailProperties.class)
public class ToolCallingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolCallingApplication.class, args);
    }
}
