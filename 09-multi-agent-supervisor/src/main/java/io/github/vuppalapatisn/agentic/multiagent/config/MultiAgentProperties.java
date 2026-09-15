package io.github.vuppalapatisn.agentic.multiagent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param maxHandoffs            delegations per run
 * @param maxModelCallsPerAgent  one specialist must not burn the whole run
 * @param maxModelCallsPerRun    run-wide ceiling, on top of the per-agent one
 * @param maxWallClock           deadline — multi-agent runs are the slowest architecture
 * @param dryRun                 kill switch
 */
@Validated
@ConfigurationProperties(prefix = "agentic.multi-agent")
public record MultiAgentProperties(

        @Min(1) int maxHandoffs,

        @Min(1) int maxModelCallsPerAgent,

        @Min(1) int maxModelCallsPerRun,

        @NotNull Duration maxWallClock,

        boolean dryRun,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @NotEmpty List<String> notificationAllowlist) {
}
