package io.github.vuppalapatisn.agentic.statemachine.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param dryRun                no money moves, no message is sent
 * @param autoApproveBelowMinor automatic tier ceiling
 * @param dualControlAboveMinor two-approver floor
 * @param maxRefundAgeDays      policy window
 * @param approvalTtl           how long an approval lives before it <b>expires</b>
 * @param settlementDelay       how long a refund can still be cancelled — the compensation window,
 *                              as a number rather than a comment
 * @param reconcileAfter        how long a run may sit in {@code PAYOUT_PENDING} before the
 *                              reconciliation sweeper asks the provider what happened
 * @param notificationAllowlist egress allowlist
 */
@Validated
@ConfigurationProperties(prefix = "agentic.state-machine")
public record StateMachineProperties(

        boolean dryRun,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @NotNull Duration approvalTtl,

        @NotNull Duration settlementDelay,

        @NotNull Duration reconcileAfter,

        @NotEmpty List<String> notificationAllowlist) {
}
