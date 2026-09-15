package io.github.vuppalapatisn.agentic.tools.gate;

import io.github.vuppalapatisn.agentic.tools.audit.AuditLog;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolDescriptor;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolRegistry;
import io.github.vuppalapatisn.agentic.tools.config.ExecutionMode;
import io.github.vuppalapatisn.agentic.tools.config.GuardrailProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The last line of defence. Every effect in this application goes through here.
 *
 * <p><b>Why this is not an advisor.</b> A Spring AI advisor wraps the {@code ChatClient} call, so it
 * only sees effects the model asked for through that one path. Anything that calls a tool bean
 * directly — another service, a scheduled job, a future refactor, a test — walks straight past it.
 * The guard therefore sits <i>inside</i> the tool boundary, and the irreversible tools have no code
 * path that does not pass through {@link #execute}.
 *
 * <p>Order of checks, all fail-closed:
 *
 * <ol>
 *   <li>the tool must be classified ({@link ToolRegistry} throws otherwise)</li>
 *   <li>per-run call ceiling</li>
 *   <li>kill switch: {@link ExecutionMode#DISABLED} refuses</li>
 *   <li>irreversible tools require an approval token whose hash matches the arguments</li>
 *   <li>idempotency: a replay returns the prior result rather than acting again</li>
 *   <li>{@link ExecutionMode#DRY_RUN} returns a synthetic result and writes nothing</li>
 *   <li>two-phase execution: record intent, act, record outcome</li>
 * </ol>
 */
@Component
public class GuardedToolExecutor {

    private final ToolRegistry registry;
    private final ApprovalStore approvals;
    private final IdempotencyLedger ledger;
    private final AuditLog auditLog;
    private final GuardrailProperties properties;

    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    public GuardedToolExecutor(ToolRegistry registry,
                               ApprovalStore approvals,
                               IdempotencyLedger ledger,
                               AuditLog auditLog,
                               GuardrailProperties properties) {
        this.registry = registry;
        this.approvals = approvals;
        this.ledger = ledger;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    /**
     * @param context  what is being done, to what, with which arguments
     * @param effect   the real effect, run only when every check passes
     * @param dryRun   builds a clearly-synthetic result from the idempotency key
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(EffectContext context, Supplier<T> effect, Function<String, T> dryRun) {
        ToolDescriptor descriptor = registry.require(context.toolName());
        String payloadHash = context.payloadHash();

        enforceCallCeiling(context, descriptor, payloadHash);

        if (!descriptor.boundaryClass().effectful()) {
            // Reads still get an audit row, but no idempotency key and no approval.
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "READ", context.businessKey(), payloadHash, false);
            return effect.get();
        }

        if (properties.executionMode() == ExecutionMode.DISABLED) {
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "REFUSED", "execution-mode=DISABLED", payloadHash, descriptor.irreversible());
            throw new ToolDisabledException(descriptor.name());
        }

        if (descriptor.irreversible()) {
            try {
                PendingApproval approval = approvals.consume(context.approvalToken(), payloadHash);
                auditLog.gate(context.runId(), descriptor.name(), approval.rule(), "CONSUMED",
                        payloadHash, approval.approvals().isEmpty()
                                ? "unknown" : approval.approvals().getFirst().approver());
            }
            catch (ApprovalException ex) {
                auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                        "REFUSED", "approval." + ex.reason(), payloadHash, true);
                throw ex;
            }
        }

        String key = context.idempotencyKey();
        var prior = ledger.find(key);
        if (prior.isPresent() && prior.get().phase() == IdempotencyLedger.Phase.APPLIED) {
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "REPLAYED", "idempotency hit", payloadHash, descriptor.irreversible());
            return (T) prior.get().result();
        }

        if (properties.executionMode() == ExecutionMode.DRY_RUN) {
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "DRY_RUN", context.businessKey(), payloadHash, descriptor.irreversible());
            return dryRun.apply(key);
        }

        // Phase 1 of two-phase execution: intent, durable and reversible.
        ledger.recordIntent(key, descriptor.name(), context.businessKey(), context.amountMinor());
        try {
            T result = effect.get();                                     // Phase 2: the effect
            ledger.recordOutcome(key, IdempotencyLedger.Phase.APPLIED, result);   // Phase 3
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "APPLIED", context.businessKey(), payloadHash, descriptor.irreversible());
            return result;
        }
        catch (RuntimeException ex) {
            ledger.recordOutcome(key, IdempotencyLedger.Phase.FAILED, null);
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "FAILED", ex.getClass().getSimpleName(), payloadHash, descriptor.irreversible());
            throw ex;
        }
    }

    private void enforceCallCeiling(EffectContext context, ToolDescriptor descriptor, String payloadHash) {
        int used = callCounts
                .computeIfAbsent(context.runId() + "/" + descriptor.name(), key -> new AtomicInteger())
                .incrementAndGet();
        int ceiling = descriptor.irreversible()
                ? Math.min(descriptor.maxCallsPerRun(), properties.maxIrreversiblePerRun())
                : descriptor.maxCallsPerRun();
        if (used > ceiling) {
            auditLog.tool(context.runId(), descriptor.name(), descriptor.boundaryClass().name(),
                    "REFUSED", "call ceiling " + ceiling + " exceeded", payloadHash,
                    descriptor.irreversible());
            throw new CallCeilingExceededException(descriptor.name(), ceiling);
        }
    }

    /** Thrown when the kill switch is set to {@code DISABLED}. */
    public static class ToolDisabledException extends RuntimeException {
        public ToolDisabledException(String toolName) {
            super("tool '" + toolName + "' is disabled by agentic.tools.execution-mode=DISABLED");
        }
    }

    /** Thrown when a tool is called more times in one run than its classification allows. */
    public static class CallCeilingExceededException extends RuntimeException {
        public CallCeilingExceededException(String toolName, int ceiling) {
            super("tool '" + toolName + "' exceeded its per-run ceiling of " + ceiling + " calls");
        }
    }
}
