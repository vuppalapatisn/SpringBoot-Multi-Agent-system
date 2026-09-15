package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.audit.AuditLog;
import io.github.vuppalapatisn.agentic.tools.boundary.BoundaryClass;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolBoundary;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolRegistry;
import io.github.vuppalapatisn.agentic.tools.config.ExecutionMode;
import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import io.github.vuppalapatisn.agentic.tools.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard's contract. These are the ten tests listed in
 * {@code docs/04-APPROVALS-AND-IRREVERSIBILITY.md} §8 — the ones that decide whether an
 * approval mechanism is real or theatre.
 */
class GuardedToolExecutorTest {

    /** A minimal classified tool surface, so the guard is tested in isolation. */
    static class TestTools {

        @Tool(name = "payOut", description = "irreversible payout")
        @ToolBoundary(value = BoundaryClass.E2, irreversible = true,
                compensation = "reverse", compensationWindow = "PT30M", maxCallsPerRun = 2)
        String payOut(String id) {
            return "paid " + id;
        }

        @Tool(name = "draft", description = "reversible internal write")
        @ToolBoundary(value = BoundaryClass.W1, maxCallsPerRun = 2)
        String draft(String id) {
            return "drafted " + id;
        }

        @Tool(name = "read", description = "internal read")
        @ToolBoundary(value = BoundaryClass.R0, maxCallsPerRun = 2)
        String read(String id) {
            return "read " + id;
        }
    }

    /** A tool someone forgot to classify. Registration of this class must fail. */
    static class UnclassifiedTools {

        @Tool(name = "wire", description = "no boundary annotation")
        String wire(String id) {
            return id;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T09:00:00Z"));
    private final ToolRegistry registry = new ToolRegistry(List.of(TestTools.class));
    private final AuditLog auditLog = new AuditLog(new SimpleMeterRegistry(), clock);
    private final IdempotencyLedger ledger = new IdempotencyLedger();
    private final AtomicInteger effects = new AtomicInteger();

    private GuardrailProperties properties(ExecutionMode mode) {
        return new GuardrailProperties(mode, Set.of("payOut"), 10_000L, 100_000L, 30,
                Duration.ofHours(24), 2, List.of("@customers.example"));
    }

    private ApprovalStore approvalStore(GuardrailProperties properties) {
        return new ApprovalStore(properties, auditLog, clock);
    }

    private GuardedToolExecutor executor(GuardrailProperties properties, ApprovalStore approvals) {
        return new GuardedToolExecutor(registry, approvals, ledger, auditLog, properties);
    }

    private EffectContext payout(String runId) {
        return EffectContext.of(runId, "payOut", "A-1187", 24_000L);
    }

    private String effect() {
        effects.incrementAndGet();
        return "applied";
    }

    // ------------------------------------------------------------------ 1

    @Test
    @DisplayName("an irreversible tool called without a token is refused, and the effect does not run")
    void refusesWithoutToken() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        GuardedToolExecutor executor = executor(properties, approvalStore(properties));

        assertThatThrownBy(() -> executor.execute(payout("r-1"), this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.MISSING_TOKEN);

        assertThat(effects).hasValue(0);
        assertThat(auditLog.forRun("r-1")).anySatisfy(record ->
                assertThat(record.outcome()).isEqualTo("REFUSED"));
    }

    // ------------------------------------------------------------------ 2

    @Test
    @DisplayName("an approval whose payload hash does not match the arguments is refused")
    void refusesPayloadMismatch() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        // Approved for $240.00 ...
        PendingApproval approval = approvals.autoApprove(payout("r-2"),
                GateDecision.auto("TEST", "approve $240.00"));

        // ... but executed for $2,400.00. This is the failure mode the hash exists to stop.
        EffectContext tampered = EffectContext.of("r-2", "payOut", "A-1187", 240_000L)
                .withToken(approval.id());

        assertThatThrownBy(() -> executor.execute(tampered, this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.PAYLOAD_MISMATCH);

        assertThat(effects).hasValue(0);
    }

    // ------------------------------------------------------------------ 3

    @Test
    @DisplayName("an expired approval is refused — expiry means no, never auto-approve")
    void refusesExpiredApproval() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval pending = approvals.request(payout("r-3"), GateDecision.single("TEST", "why"));
        approvals.approve(pending.id(), "u-114");

        clock.advance(Duration.ofHours(25));

        assertThatThrownBy(() -> executor.execute(payout("r-3").withToken(pending.id()),
                this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.EXPIRED);

        assertThat(effects).hasValue(0);
        assertThat(approvals.find(pending.id()).orElseThrow().status())
                .isEqualTo(ApprovalStatus.EXPIRED);
    }

    // ------------------------------------------------------------------ 4

    @Test
    @DisplayName("resuming twice with the same token executes the effect once")
    void replaySafe() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval approval = approvals.autoApprove(payout("r-4"), GateDecision.auto("TEST", "why"));
        EffectContext context = payout("r-4").withToken(approval.id());

        executor.execute(context, this::effect, key -> "dry");

        assertThatThrownBy(() -> executor.execute(context, this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.ALREADY_CONSUMED);

        assertThat(effects).hasValue(1);
    }

    @Test
    @DisplayName("a second identical effect with a fresh approval is a replay, not a second payment")
    void idempotencyStopsASecondPayment() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval first = approvals.autoApprove(payout("r-5"), GateDecision.auto("TEST", "why"));
        PendingApproval second = approvals.autoApprove(payout("r-5"), GateDecision.auto("TEST", "why"));

        String one = executor.execute(payout("r-5").withToken(first.id()), this::effect, key -> "dry");
        String two = executor.execute(payout("r-5").withToken(second.id()), this::effect, key -> "dry");

        assertThat(one).isEqualTo("applied");
        assertThat(two).isEqualTo("applied");        // the prior result, replayed
        assertThat(effects).hasValue(1);             // but the effect ran once
        assertThat(auditLog.forRun("r-5")).anySatisfy(record ->
                assertThat(record.outcome()).isEqualTo("REPLAYED"));
    }

    // ------------------------------------------------------------------ 5

    @Test
    @DisplayName("a rejected approval cannot authorise anything")
    void refusesRejectedApproval() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval pending = approvals.request(payout("r-6"), GateDecision.single("TEST", "why"));
        approvals.reject(pending.id(), "u-114");

        assertThatThrownBy(() -> executor.execute(payout("r-6").withToken(pending.id()),
                this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.REJECTED);

        assertThat(effects).hasValue(0);
    }

    // ------------------------------------------------------------------ 6

    @Test
    @DisplayName("dual control is not satisfied by one approver approving twice")
    void dualControlNeedsTwoDistinctApprovers() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval pending = approvals.request(payout("r-7"), GateDecision.dual("TEST", "why"));
        approvals.approve(pending.id(), "u-114");

        assertThatThrownBy(() -> approvals.approve(pending.id(), "u-114"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.DUPLICATE_APPROVER);

        assertThatThrownBy(() -> executor.execute(payout("r-7").withToken(pending.id()),
                this::effect, key -> "dry"))
                .isInstanceOf(ApprovalException.class)
                .extracting(ex -> ((ApprovalException) ex).reason())
                .isEqualTo(ApprovalException.Reason.NOT_APPROVED);

        approvals.approve(pending.id(), "u-220");
        executor.execute(payout("r-7").withToken(pending.id()), this::effect, key -> "dry");

        assertThat(effects).hasValue(1);
    }

    // ------------------------------------------------------------------ 7

    @Test
    @DisplayName("DRY_RUN performs no writes and returns a clearly synthetic result")
    void dryRunWritesNothing() {
        GuardrailProperties properties = properties(ExecutionMode.DRY_RUN);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval approval = approvals.autoApprove(payout("r-8"), GateDecision.auto("TEST", "why"));
        String result = executor.execute(payout("r-8").withToken(approval.id()),
                this::effect, key -> "synthetic:" + key.substring(0, 6));

        assertThat(result).startsWith("synthetic:");
        assertThat(effects).hasValue(0);
        assertThat(ledger.find(payout("r-8").idempotencyKey())).isEmpty();
    }

    // ------------------------------------------------------------------ 8

    @Test
    @DisplayName("the kill switch refuses every effectful tool")
    void killSwitchRefuses() {
        GuardrailProperties properties = properties(ExecutionMode.DISABLED);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval approval = approvals.autoApprove(payout("r-9"), GateDecision.auto("TEST", "why"));

        assertThatThrownBy(() -> executor.execute(payout("r-9").withToken(approval.id()),
                this::effect, key -> "dry"))
                .isInstanceOf(GuardedToolExecutor.ToolDisabledException.class);

        assertThatThrownBy(() -> executor.execute(
                EffectContext.of("r-9", "draft", "A-1187", 24_000L), this::effect, key -> "dry"))
                .isInstanceOf(GuardedToolExecutor.ToolDisabledException.class);

        assertThat(effects).hasValue(0);
    }

    @Test
    @DisplayName("reads still work when effects are disabled — a kill switch must not blind you")
    void killSwitchLeavesReadsWorking() {
        GuardrailProperties properties = properties(ExecutionMode.DISABLED);
        GuardedToolExecutor executor = executor(properties, approvalStore(properties));

        String result = executor.execute(EffectContext.of("r-10", "read", "A-1187", 0L),
                () -> "read A-1187", key -> "dry");

        assertThat(result).isEqualTo("read A-1187");
    }

    // ------------------------------------------------------------------ 9

    @Test
    @DisplayName("the per-run call ceiling is enforced by the guard, not only by the framework")
    void enforcesCallCeiling() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        for (int i = 0; i < 2; i++) {
            EffectContext context = EffectContext.of("r-11", "draft", "A-118" + i, 100L);
            executor.execute(context, this::effect, key -> "dry");
        }

        assertThatThrownBy(() -> executor.execute(
                EffectContext.of("r-11", "draft", "A-9999", 100L), this::effect, key -> "dry"))
                .isInstanceOf(GuardedToolExecutor.CallCeilingExceededException.class);

        assertThat(effects).hasValue(2);
    }

    // ----------------------------------------------------------------- 10

    @Test
    @DisplayName("a crash between intent and effect leaves an unreconciled entry, not a mystery")
    void recordsIntentBeforeTheEffect() {
        GuardrailProperties properties = properties(ExecutionMode.EXECUTE);
        ApprovalStore approvals = approvalStore(properties);
        GuardedToolExecutor executor = executor(properties, approvals);

        PendingApproval approval = approvals.autoApprove(payout("r-12"), GateDecision.auto("TEST", "why"));

        assertThatThrownBy(() -> executor.execute(payout("r-12").withToken(approval.id()),
                () -> {
                    throw new IllegalStateException("provider timeout");
                },
                key -> "dry"))
                .isInstanceOf(IllegalStateException.class);

        String key = payout("r-12").idempotencyKey();
        assertThat(ledger.find(key)).isPresent().get()
                .extracting(IdempotencyLedger.Entry::phase)
                .isEqualTo(IdempotencyLedger.Phase.FAILED);
        // The key survives, so a sweeper can ask the provider "did this happen?" by key.
        assertThat(key).isEqualTo(payout("r-12").idempotencyKey());
    }

    @Test
    @DisplayName("an unclassified tool cannot be registered at all")
    void unclassifiedToolIsRejected() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(UnclassifiedTools.class)))
                .isInstanceOf(ToolRegistry.UnclassifiedToolException.class)
                .hasMessageContaining("@ToolBoundary");
    }

    @Test
    @DisplayName("an idempotency key is stable across attempts and independent of model output")
    void idempotencyKeyIsStable() {
        String first = IdempotencyKey.of("r-13", "payOut", "A-1187", 24_000L);
        String second = IdempotencyKey.of("r-13", "payOut", "A-1187", 24_000L);
        String different = IdempotencyKey.of("r-13", "payOut", "A-1187", 24_001L);

        assertThat(first).isEqualTo(second).isNotEqualTo(different);
    }
}
