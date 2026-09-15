package io.github.vuppalapatisn.agentic.statemachine.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The persisted idempotency ledger — two-phase execution, in a table.
 *
 * <pre>
 *   recordIntent(key)   ── phase 1, durable and reversible
 *        ▼
 *   the effect          ── phase 2, irreversible
 *        ▼
 *   recordOutcome(key)  ── phase 3
 * </pre>
 *
 * <p>The primary key on {@code idempotency_key} is the control, not the code around it: a second
 * attempt to record the same intent fails at the database, so two concurrent callers cannot both
 * proceed to the effect. A crash between phases 1 and 2 leaves an {@code INTENT} row, which is
 * exactly what a reconciliation sweeper needs — it can ask the provider "did this key happen?"
 * rather than guess.
 */
@Repository
public class EffectLedger {

    public enum Phase {
        INTENT, APPLIED, FAILED, COMPENSATED
    }

    public record Entry(String idempotencyKey, String runId, String effect, String businessKey,
                        long amountMinor, Phase phase, String resultRef,
                        Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public EffectLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Phase 1.
     *
     * @return true when this caller recorded the intent and may proceed to the effect; false when
     *         an entry already exists, meaning this is a replay or a concurrent attempt
     */
    public boolean recordIntent(String key, String runId, String effect, String businessKey, long amountMinor) {
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                            INSERT INTO refund_effect (idempotency_key, run_id, effect, business_key,
                                                       amount_minor, phase, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(key, runId, effect, businessKey, amountMinor, Phase.INTENT.name(),
                            Timestamp.from(now), Timestamp.from(now))
                    .update();
            return true;
        }
        catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** Phase 3. */
    public void recordOutcome(String key, Phase phase, String resultRef) {
        jdbc.sql("UPDATE refund_effect SET phase = ?, result_ref = ?, updated_at = ? WHERE idempotency_key = ?")
                .params(phase.name(), resultRef, Timestamp.from(clock.instant()), key)
                .update();
    }

    public Optional<Entry> find(String key) {
        return jdbc.sql("SELECT * FROM refund_effect WHERE idempotency_key = ?")
                .param(key)
                .query((rs, rowNum) -> new Entry(
                        rs.getString("idempotency_key"),
                        rs.getString("run_id"),
                        rs.getString("effect"),
                        rs.getString("business_key"),
                        rs.getLong("amount_minor"),
                        Phase.valueOf(rs.getString("phase")),
                        rs.getString("result_ref"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /** Entries a reconciliation sweeper must resolve against the provider. */
    public List<String> unreconciledKeys(Instant olderThan) {
        return jdbc.sql("SELECT idempotency_key FROM refund_effect WHERE phase = ? AND created_at < ?")
                .params(Phase.INTENT.name(), Timestamp.from(olderThan))
                .query(String.class)
                .list();
    }

    public int appliedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM refund_effect WHERE phase = ?")
                .param(Phase.APPLIED.name())
                .query(Integer.class)
                .single();
    }
}
