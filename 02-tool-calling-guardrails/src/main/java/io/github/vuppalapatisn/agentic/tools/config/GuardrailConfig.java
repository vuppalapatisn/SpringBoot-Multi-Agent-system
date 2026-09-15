package io.github.vuppalapatisn.agentic.tools.config;

import io.github.vuppalapatisn.agentic.tools.boundary.ToolBoundary;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolDescriptor;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolRegistry;
import io.github.vuppalapatisn.agentic.tools.tools.RefundTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class GuardrailConfig {

    private static final Logger log = LoggerFactory.getLogger(GuardrailConfig.class);

    /** Injected everywhere instead of {@code Instant.now()}, so expiry and settlement are testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ToolRegistry toolRegistry() {
        return new ToolRegistry(List.of(RefundTools.class));
    }

    /**
     * Startup gate. Two invariants, both of which fail the application rather than production:
     *
     * <ol>
     *   <li>every {@code @Tool} method carries {@link ToolBoundary} — enforced by
     *       {@link ToolRegistry}'s constructor, so an unclassified tool cannot even be registered;</li>
     *   <li>every tool marked {@code irreversible} appears in
     *       {@code agentic.tools.approval-required-tools}, so the code and the operational policy
     *       cannot drift apart.</li>
     * </ol>
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> toolBoundaryValidator(ToolRegistry registry,
                                                                     GuardrailProperties properties) {
        return event -> {
            Set<String> declared = properties.approvalRequiredTools();
            List<String> irreversible = registry.irreversible().stream()
                    .map(ToolDescriptor::name).toList();

            List<String> unregistered = irreversible.stream()
                    .filter(name -> !declared.contains(name))
                    .toList();
            if (!unregistered.isEmpty()) {
                throw new IllegalStateException(
                        "irreversible tools missing from agentic.tools.approval-required-tools: "
                                + unregistered + ". Classify the policy, not just the code.");
            }

            List<String> unknown = declared.stream()
                    .filter(name -> registry.find(name).isEmpty())
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalStateException(
                        "agentic.tools.approval-required-tools names tools that do not exist: " + unknown);
            }

            log.info("tool boundary inventory (execution-mode={}):\n{}",
                    properties.executionMode(),
                    registry.all().values().stream()
                            .map(descriptor -> "  %-20s %-3s %s%s".formatted(
                                    descriptor.name(),
                                    descriptor.boundaryClass(),
                                    descriptor.irreversible() ? "IRREVERSIBLE" : "reversible",
                                    descriptor.compensable()
                                            ? " (compensation " + descriptor.compensation()
                                              + " within " + descriptor.compensationWindow() + ")" : ""))
                            .collect(Collectors.joining("\n")));
        };
    }
}
