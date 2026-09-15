package io.github.vuppalapatisn.agentic.tools.audit;

import java.time.Instant;

/**
 * One append-only audit row.
 *
 * @param runId       correlation id
 * @param seq         order within the run
 * @param kind        {@code TOOL} or {@code GATE}
 * @param name        tool name or gate rule
 * @param outcome     e.g. {@code APPLIED}, {@code REFUSED}, {@code DRY_RUN}, {@code APPROVED}
 * @param detail      short, redacted description
 * @param payloadHash hash of the frozen arguments, so a reviewer can tie a gate to an execution
 * @param actor       approver, {@code policy:<rule>}, {@code model}, or {@code sweeper}
 * @param at          timestamp
 */
public record AuditRecord(
        String runId,
        int seq,
        String kind,
        String name,
        String outcome,
        String detail,
        String payloadHash,
        String actor,
        Instant at) {
}
