package io.github.vuppalapatisn.agentic.statemachine.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The human gate, as data.
 *
 * <p>In project 06 an approval was an object in a map, so a restart lost it. Here it is a row with
 * an expiry, and that single difference is most of the reason to choose a state machine: the run
 * has somewhere to <b>be</b> while a person takes a day to decide.
 *
 * <p>All seven properties of a real approval are storage-level facts rather than code conventions:
 * durable (a row), frozen ({@code amount_minor} + {@code payload_hash}), attributed
 * ({@code approvers}), bounded ({@code expires_at}), replay-safe (status transitions to
 * {@code EXECUTED} once), legible ({@code explanation}), refusable ({@code REJECTED}).
 */
@Repository
public class ApprovalRepository {

    public enum Status {
        PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED
    }

    public record Approval(String approvalId, String runId, String orderId, long amountMinor,
                           String payloadHash, String rule, String explanation,
                           int requiredApprovals, List<String> approvers, Status status,
                           Instant createdAt, Instant expiresAt) {

        public boolean fullyApproved() {
            return approvers.size() >= requiredApprovals;
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public ApprovalRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Approval create(String runId, String orderId, long amountMinor, String payloadHash,
                           String rule, String explanation, int requiredApprovals, Duration ttl) {
        Instant now = clock.instant();
        String approvalId = "ap-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                        INSERT INTO run_approval (approval_id, run_id, order_id, amount_minor, payload_hash,
                                                  rule, explanation, required_approvals, approvers, status,
                                                  created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?)
                        """)
                .params(approvalId, runId, orderId, amountMinor, payloadHash, rule, explanation,
                        requiredApprovals, Status.PENDING.name(), Timestamp.from(now),
                        Timestamp.from(now.plus(ttl)))
                .update();
        return find(approvalId).orElseThrow();
    }

    /**
     * Adds one approver, guarded so that the same person cannot satisfy dual control twice and a
     * concurrent caller cannot double-append.
     *
     * @return the updated approval, or empty when the approval is not actionable
     */
    public Optional<Approval> addApprover(String approvalId, String approver) {
        Approval approval = find(approvalId).orElse(null);
        if (approval == null || approval.status() != Status.PENDING
                || approval.approvers().contains(approver)
                || clock.instant().isAfter(approval.expiresAt())) {
            return Optional.empty();
        }
        List<String> approvers = java.util.stream.Stream
                .concat(approval.approvers().stream(), java.util.stream.Stream.of(approver)).toList();
        Status next = approvers.size() >= approval.requiredApprovals() ? Status.APPROVED : Status.PENDING;

        int affected = jdbc.sql("""
                        UPDATE run_approval SET approvers = ?, status = ?
                        WHERE approval_id = ? AND status = ? AND approvers = ?
                        """)
                .params(String.join(",", approvers), next.name(), approvalId,
                        Status.PENDING.name(), String.join(",", approval.approvers()))
                .update();
        return affected == 0 ? Optional.empty() : find(approvalId);
    }

    public Optional<Approval> reject(String approvalId, String approver) {
        int affected = jdbc.sql("""
                        UPDATE run_approval SET status = ?, approvers = ?
                        WHERE approval_id = ? AND status = ?
                        """)
                .params(Status.REJECTED.name(), approver, approvalId, Status.PENDING.name())
                .update();
        return affected == 0 ? Optional.empty() : find(approvalId);
    }

    /** Single-use. A duplicated resume finds the approval already {@code EXECUTED} and stops. */
    public Optional<Approval> consume(String approvalId, String expectedPayloadHash) {
        Approval approval = find(approvalId).orElse(null);
        if (approval == null || approval.status() != Status.APPROVED || !approval.fullyApproved()) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(approval.expiresAt())) {
            expire(approvalId);
            return Optional.empty();
        }
        if (!approval.payloadHash().equals(expectedPayloadHash)) {
            // What runs is not what was approved. Refuse; do not "fix up" the payload.
            return Optional.empty();
        }
        int affected = jdbc.sql("UPDATE run_approval SET status = ? WHERE approval_id = ? AND status = ?")
                .params(Status.EXECUTED.name(), approvalId, Status.APPROVED.name())
                .update();
        return affected == 0 ? Optional.empty() : find(approvalId);
    }

    public void expire(String approvalId) {
        jdbc.sql("UPDATE run_approval SET status = ? WHERE approval_id = ? AND status = ?")
                .params(Status.EXPIRED.name(), approvalId, Status.PENDING.name())
                .update();
    }

    /** What the expiry sweeper works through. Expiry means <b>no</b>. */
    public List<Approval> findExpired(Instant now) {
        return jdbc.sql("SELECT * FROM run_approval WHERE status = ? AND expires_at < ?")
                .params(Status.PENDING.name(), Timestamp.from(now))
                .query(this::map)
                .list();
    }

    public List<Approval> pending() {
        return jdbc.sql("SELECT * FROM run_approval WHERE status IN (?, ?) ORDER BY created_at")
                .params(Status.PENDING.name(), Status.APPROVED.name())
                .query(this::map)
                .list();
    }

    public Optional<Approval> find(String approvalId) {
        return jdbc.sql("SELECT * FROM run_approval WHERE approval_id = ?")
                .param(approvalId)
                .query(this::map)
                .optional();
    }

    public Optional<Approval> findByRun(String runId) {
        return jdbc.sql("SELECT * FROM run_approval WHERE run_id = ? ORDER BY created_at DESC LIMIT 1")
                .param(runId)
                .query(this::map)
                .optional();
    }

    private Approval map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String approvers = rs.getString("approvers");
        return new Approval(
                rs.getString("approval_id"),
                rs.getString("run_id"),
                rs.getString("order_id"),
                rs.getLong("amount_minor"),
                rs.getString("payload_hash"),
                rs.getString("rule"),
                rs.getString("explanation"),
                rs.getInt("required_approvals"),
                approvers == null || approvers.isBlank()
                        ? List.of() : Arrays.stream(approvers.split(",")).toList(),
                Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }
}
