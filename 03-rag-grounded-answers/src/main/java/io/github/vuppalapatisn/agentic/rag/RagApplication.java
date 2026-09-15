package io.github.vuppalapatisn.agentic.rag;

import io.github.vuppalapatisn.agentic.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 03 — retrieval-augmented answers with grounding gates.
 *
 * <p>Read-only like project 01, so the irreversible-action catalogue is empty again. The risks here
 * are different in kind: fabricated citations, cross-tenant retrieval, and injected instructions
 * arriving inside a retrieved document. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
