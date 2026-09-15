package io.github.vuppalapatisn.agentic.tools.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Append-only audit of tool calls and gate decisions, plus the guardrail metrics from Phase 9.
 *
 * <p>The metrics chosen here are the two people usually forget and then wish they had:
 * {@code agentic.gate.decision} (a rising rejection rate means the model's judgement drifted, or
 * someone is probing it) and {@code agentic.tool.irreversible} (volume of one-way doors actually
 * opened). Alert on the rate of change, not the absolute value.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final Map<String, List<AuditRecord>> byRun = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final Clock clock;

    public AuditLog(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    public void tool(String runId, String toolName, String boundaryClass, String outcome,
                     String detail, String payloadHash, boolean irreversible) {
        append(new AuditRecord(runId, 0, "TOOL", toolName, outcome, detail, payloadHash, "model",
                clock.instant()));
        meters.counter("agentic.tool.call",
                "tool", toolName, "class", boundaryClass, "outcome", outcome).increment();
        if (irreversible && "APPLIED".equals(outcome)) {
            meters.counter("agentic.tool.irreversible", "tool", toolName).increment();
        }
    }

    public void gate(String runId, String toolName, String rule, String outcome,
                     String payloadHash, String actor) {
        append(new AuditRecord(runId, 0, "GATE", rule, outcome,
                "tool=" + toolName, payloadHash, actor, clock.instant()));
        meters.counter("agentic.gate.decision", "rule", rule, "outcome", outcome).increment();
    }

    private void append(AuditRecord partial) {
        int seq = sequences.computeIfAbsent(partial.runId(), key -> new AtomicInteger()).incrementAndGet();
        AuditRecord record = new AuditRecord(partial.runId(), seq, partial.kind(), partial.name(),
                partial.outcome(), partial.detail(), partial.payloadHash(), partial.actor(), partial.at());
        byRun.computeIfAbsent(partial.runId(), key -> new CopyOnWriteArrayList<>()).add(record);
        log.info("audit run={} seq={} {} {} -> {} ({}) actor={} hash={}",
                record.runId(), record.seq(), record.kind(), record.name(), record.outcome(),
                record.detail(), record.actor(), abbreviate(record.payloadHash()));
    }

    public List<AuditRecord> forRun(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    private static String abbreviate(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(8, hash.length()));
    }
}
