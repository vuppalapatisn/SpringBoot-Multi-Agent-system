package io.github.vuppalapatisn.agentic.foundation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for this project. Typed and validated, so a misconfiguration is a boot failure
 * rather than a runtime surprise.
 *
 * @param classifierModel model used for classification — the cheapest model that passes the evals
 * @param maxTokens       completion ceiling; part of the per-run cost ceiling (Phase 0)
 * @param requestTimeout  every model call is bounded (Phase 8)
 * @param memoryWindow    how many messages of conversation are replayed to the model
 * @param maxInputChars   hard cap on untrusted customer text entering the context window
 */
@Validated
@ConfigurationProperties(prefix = "agentic.foundation")
public record FoundationProperties(

        @NotBlank String classifierModel,

        @Min(64) @Max(8192) int maxTokens,

        Duration requestTimeout,

        @Min(2) @Max(200) int memoryWindow,

        @Min(200) @Max(20_000) int maxInputChars) {

    public FoundationProperties {
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(30);
        }
    }
}
