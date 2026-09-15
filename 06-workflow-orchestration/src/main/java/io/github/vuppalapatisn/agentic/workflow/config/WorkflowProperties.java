package io.github.vuppalapatisn.agentic.workflow.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * @param dryRun                  kill switch: no money moves, no message is sent
 * @param autoApproveBelowMinor   automatic tier ceiling
 * @param dualControlAboveMinor   two-approver floor
 * @param maxRefundAgeDays        policy window for the automatic tier
 * @param notificationAllowlist   egress allowlist (domain suffixes)
 */
@Validated
@ConfigurationProperties(prefix = "agentic.workflow")
public record WorkflowProperties(

        boolean dryRun,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @NotEmpty List<String> notificationAllowlist) {
}
