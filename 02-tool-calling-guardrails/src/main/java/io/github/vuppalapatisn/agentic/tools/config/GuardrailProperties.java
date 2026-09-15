package io.github.vuppalapatisn.agentic.tools.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Every guardrail threshold in one validated place.
 *
 * @param executionMode          the kill switch
 * @param approvalRequiredTools  tools that may not execute without an approval token. Startup fails
 *                               if an {@code irreversible} tool is missing from this set, so the
 *                               policy and the code cannot drift apart.
 * @param autoApproveBelowMinor  tier 1 ceiling: below this, a policy gate decides with no human
 * @param dualControlAboveMinor  tier 3 floor: above this, two approvers are required
 * @param maxRefundAgeDays       policy window for an automatic approval
 * @param approvalTtl            how long a pending approval lives before it <b>expires</b>.
 *                               It never auto-approves.
 * @param maxIrreversiblePerRun  hard ceiling on irreversible calls in a single run
 * @param notificationAllowlist  egress allowlist for customer notifications (domain suffixes)
 */
@Validated
@ConfigurationProperties(prefix = "agentic.tools")
public record GuardrailProperties(

        @NotNull ExecutionMode executionMode,

        @NotEmpty Set<String> approvalRequiredTools,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @NotNull Duration approvalTtl,

        @Min(1) int maxIrreversiblePerRun,

        @NotEmpty List<String> notificationAllowlist) {

    public GuardrailProperties {
        if (dualControlAboveMinor > 0 && dualControlAboveMinor < autoApproveBelowMinor) {
            throw new IllegalArgumentException(
                    "dual-control-above-minor (" + dualControlAboveMinor + ") must not be below "
                            + "auto-approve-below-minor (" + autoApproveBelowMinor + ")");
        }
    }
}
