package io.github.vuppalapatisn.agentic.tools.gate;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which effects have already been applied, keyed by idempotency key, so that a replay
 * returns the prior result instead of performing the effect twice.
 *
 * <p>Two-phase execution lives here (see {@code docs/04-APPROVALS-AND-IRREVERSIBILITY.md} §4):
 * {@link #recordIntent} is phase 1 and is reversible; {@link #recordOutcome} is phase 3. A crash
 * between them leaves an {@code INTENT} entry that a reconciliation sweeper can resolve against the
 * provider <b>by the same key</b> — it can ask "did this happen?" instead of guessing.
 *
 * <p>In memory here; a table with a unique index on the key in production. Project 07 persists it.
 */
@Component
public class IdempotencyLedger {

    public enum Phase {
        /** Intent recorded, effect not yet attempted or outcome unknown. */
        INTENT,
        /** Effect confirmed applied. */
        APPLIED,
        /** Effect confirmed failed; safe to retry with the same key. */
        FAILED
    }

    public record Entry(String key, Phase phase, String toolName, String businessKey,
                        long amountMinor, Object result) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Phase 1. Returns false when an entry already exists, meaning this is a replay. */
    public boolean recordIntent(String key, String toolName, String businessKey, long amountMinor) {
        return entries.putIfAbsent(key,
                new Entry(key, Phase.INTENT, toolName, businessKey, amountMinor, null)) == null;
    }

    /** Phase 3. */
    public void recordOutcome(String key, Phase phase, Object result) {
        entries.computeIfPresent(key, (k, existing) -> new Entry(
                k, phase, existing.toolName(), existing.businessKey(), existing.amountMinor(), result));
    }

    public Optional<Entry> find(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /** Entries stuck in {@code INTENT} — what a reconciliation sweeper works through. */
    public java.util.List<Entry> unreconciled() {
        return entries.values().stream().filter(entry -> entry.phase() == Phase.INTENT).toList();
    }

    public void clear() {
        entries.clear();
    }
}
