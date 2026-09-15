package io.github.vuppalapatisn.agentic.agentloop.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Every budget and threshold for the agent loop, in one validated record.
 *
 * <p>The numbers below are the design. An agent architecture's worst case is <b>whatever you capped
 * it at</b>, which is why the cap is mandatory and why it lives in configuration a reviewer can
 * read rather than scattered through the code.
 *
 * @param maxSteps                     model turns per run
 * @param maxToolCalls                 tool calls per run
 * @param maxCallsPerToolDefault       per-tool ceiling when not named below
 * @param maxCallsPerTool              per-tool ceilings; irreversible tools get 1
 * @param maxTokens                    prompt + completion tokens per run
 * @param maxCostMinor                 estimated spend ceiling per run
 * @param maxWallClock                 deadline a waiting human feels
 * @param maxNoProgressSteps           consecutive identical tool calls before the run is stopped
 * @param promptCostPerMillionMinor    pricing input for the cost estimate
 * @param completionCostPerMillionMinor pricing input for the cost estimate
 * @param reflectionEnabled            run one bounded self-check pass before finishing
 * @param dryRun                       kill switch
 */
@Validated
@ConfigurationProperties(prefix = "agentic.agent")
public record AgentProperties(

        @Min(1) int maxSteps,

        @Min(1) int maxToolCalls,

        @Min(1) int maxCallsPerToolDefault,

        @NotNull Map<String, Integer> maxCallsPerTool,

        @Min(100) int maxTokens,

        @Min(1) long maxCostMinor,

        @NotNull Duration maxWallClock,

        @Min(1) int maxNoProgressSteps,

        @Min(0) long promptCostPerMillionMinor,

        @Min(0) long completionCostPerMillionMinor,

        boolean reflectionEnabled,

        boolean dryRun,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @NotEmpty List<String> notificationAllowlist) {
}
