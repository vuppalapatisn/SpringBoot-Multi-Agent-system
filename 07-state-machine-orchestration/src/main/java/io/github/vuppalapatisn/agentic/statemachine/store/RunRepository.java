package io.github.vuppalapatisn.agentic.statemachine.store;

import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.Transition;
import io.github.vuppalapatisn.agentic.statemachine.domain.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persisted runs and their transition history.
 *
 * <p>The important method is {@link #transition}. It is the <b>only</b> way the {@code state}
 * column changes, and it enforces two things at once:
 *
 * <ol>
 *   <li><b>The transition table</b> — a move not listed in {@link RunState#allowedNext()} is
 *       refused, so an illegal transition is a bug caught at the boundary rather than a corrupt
 *       row discovered later.</li>
 *   <li><b>Optimistic concurrency</b> — {@code UPDATE ... WHERE run_id = ? AND state = ?}. If two
 *       callers race (a sweeper and an API request, say), exactly one update affects a row and the
 *       other is told it lost. This is what stops a run being paid twice by two threads that both
 *       read {@code AWAITING_APPROVAL}.</li>
 * </ol>
 *
 * <p>Every successful transition also appends an immutable row to {@code run_transition}, giving the
 * audit trail a regulator asks for: what state was this run in, when, and who moved it.
 */
@Repository
public class RunRepository {

    private static final Logger log = LoggerFactory.getLogger(RunRepository.class);

    /** Columns a transition is allowed to write. Keys are code-controlled; validated regardless. */
    private static final Set<String> UPDATABLE = Set.of(
            "gate_rule", "decision_outcome", "decision_clause", "decision_risk", "fraud_signal",
            "customer_reply", "receipt_id", "idempotency_key", "settles_at", "failure_reason");

    private final JdbcClient jdbc;
    private final Clock clock;

    public RunRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public RefundRun create(String runId, OrderSummary order, String customerMessage) {
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO refund_run (run_id, order_id, customer_id, customer_email, amount_minor,
                                                currency, state, customer_message, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(runId, order.orderId(), order.customerId(), order.customerEmail(),
                        order.totalMinor(), order.currency(), RunState.CREATED.name(),
                        customerMessage, Timestamp.from(now), Timestamp.from(now))
                .update();
        appendTransition(runId, RunState.CREATED, RunState.CREATED, "system", "run created", now);
        return find(runId).orElseThrow();
    }

    /**
     * Moves a run from {@code expected} to {@code next}, atomically, writing any extra fields in the
     * same statement.
     *
     * @return true when this caller performed the transition; false when the run was not in
     *         {@code expected} (someone else got there first, or the state has already moved on)
     * @throws IllegalStateException when the move is not in the transition table
     */
    @Transactional
    public boolean transition(String runId, RunState expected, RunState next,
                              String actor, String reason, Map<String, Object> fields) {
        if (!expected.canMoveTo(next) && expected != next) {
            throw new IllegalStateException(
                    "illegal transition " + expected + " -> " + next + " for run " + runId);
        }

        Map<String, Object> updates = new LinkedHashMap<>(fields == null ? Map.of() : fields);
        updates.keySet().forEach(column -> {
            if (!UPDATABLE.contains(column)) {
                throw new IllegalArgumentException("column '" + column + "' is not updatable by a transition");
            }
        });

        StringBuilder sql = new StringBuilder("UPDATE refund_run SET state = ?, updated_at = ?");
        updates.keySet().forEach(column -> sql.append(", ").append(column).append(" = ?"));
        sql.append(" WHERE run_id = ? AND state = ?");

        Instant now = clock.instant();
        Object[] params = new Object[3 + updates.size() + 1];
        int index = 0;
        params[index++] = next.name();
        params[index++] = Timestamp.from(now);
        for (Object value : updates.values()) {
            params[index++] = value instanceof Instant instant ? Timestamp.from(instant) : value;
        }
        params[index++] = runId;
        params[index] = expected.name();

        int affected = jdbc.sql(sql.toString()).params(params).update();
        if (affected == 0) {
            log.warn("transition {} -> {} for run {} lost the race (run was not in {})",
                    expected, next, runId, expected);
            return false;
        }
        appendTransition(runId, expected, next, actor, reason, now);
        return true;
    }

    private void appendTransition(String runId, RunState from, RunState to,
                                  String actor, String reason, Instant at) {
        Integer previous = jdbc.sql("SELECT MAX(seq) FROM run_transition WHERE run_id = ?")
                .param(runId).query(Integer.class).optional().orElse(0);
        jdbc.sql("""
                        INSERT INTO run_transition (run_id, seq, from_state, to_state, actor, reason, at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(runId, previous == null ? 1 : previous + 1, from.name(), to.name(), actor,
                        reason, Timestamp.from(at))
                .update();
    }

    public Optional<RefundRun> find(String runId) {
        return jdbc.sql("SELECT * FROM refund_run WHERE run_id = ?")
                .param(runId)
                .query(RunRepository::mapRun)
                .optional();
    }

    public Optional<String> customerMessage(String runId) {
        return jdbc.sql("SELECT customer_message FROM refund_run WHERE run_id = ?")
                .param(runId).query(String.class).optional();
    }

    public List<Transition> transitions(String runId) {
        return jdbc.sql("SELECT * FROM run_transition WHERE run_id = ? ORDER BY seq")
                .param(runId)
                .query((rs, rowNum) -> new Transition(
                        rs.getInt("seq"),
                        RunState.valueOf(rs.getString("from_state")),
                        RunState.valueOf(rs.getString("to_state")),
                        rs.getString("actor"),
                        rs.getString("reason"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    public List<RefundRun> findByState(RunState state) {
        return jdbc.sql("SELECT * FROM refund_run WHERE state = ? ORDER BY created_at")
                .param(state.name())
                .query(RunRepository::mapRun)
                .list();
    }

    /** Runs stuck between recording the intent to pay and learning the outcome. */
    public List<RefundRun> findStalePayouts(Instant olderThan) {
        return jdbc.sql("SELECT * FROM refund_run WHERE state = ? AND updated_at < ? ORDER BY updated_at")
                .params(RunState.PAYOUT_PENDING.name(), Timestamp.from(olderThan))
                .query(RunRepository::mapRun)
                .list();
    }

    private static RefundRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        Timestamp settles = rs.getTimestamp("settles_at");
        return new RefundRun(
                rs.getString("run_id"),
                rs.getString("order_id"),
                rs.getString("customer_id"),
                rs.getString("customer_email"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                RunState.valueOf(rs.getString("state")),
                rs.getString("gate_rule"),
                rs.getString("decision_outcome"),
                rs.getString("decision_clause"),
                rs.getString("decision_risk"),
                rs.getString("fraud_signal"),
                rs.getString("customer_reply"),
                rs.getString("receipt_id"),
                rs.getString("idempotency_key"),
                settles == null ? null : settles.toInstant(),
                rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
