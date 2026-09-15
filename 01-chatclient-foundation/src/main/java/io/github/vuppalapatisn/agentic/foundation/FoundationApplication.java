package io.github.vuppalapatisn.agentic.foundation;

import io.github.vuppalapatisn.agentic.foundation.config.FoundationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 01 — ChatClient foundation.
 *
 * <p>Control-flow graph: {@code docs/CFG.md}. This project is deliberately <b>read-only</b>: it has
 * no tools, no writes and no egress, so its irreversible-action catalogue is empty. That is what
 * makes it the right place to learn the mechanics before any gate is needed.
 */
@SpringBootApplication
@EnableConfigurationProperties(FoundationProperties.class)
public class FoundationApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoundationApplication.class, args);
    }
}
