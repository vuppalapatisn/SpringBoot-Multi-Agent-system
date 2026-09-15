package io.github.vuppalapatisn.agentic.statemachine.machine;

import io.github.vuppalapatisn.agentic.statemachine.config.StateMachineProperties;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.store.ApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * The two background duties a durable state machine owes you. Both are <b>sweepers over persisted
 * state</b>, not in-memory timers, which is why they still work after a restart.
 *
 * <ol>
 *   <li><b>Expiry.</b> An approval nobody answers becomes {@code EXPIRED}. It never becomes
 *       approved: the unavailability of a human must not turn into a yes.</li>
 *   <li><b>Reconciliation.</b> A run stuck in {@code PAYOUT_PENDING} is a run where we recorded the
 *       intent to pay and never learned the outcome — a crash, a timeout, a lost acknowledgement.
 *       The sweeper asks the provider, <i>by idempotency key</i>, whether it happened. That question
 *       is only askable because the key was written before the effect.</li>
 * </ol>
 */
@Component
public class Sweepers {

    private static final Logger log = LoggerFactory.getLogger(Sweepers.class);

    private final io.github.vuppalapatisn.agentic.statemachine.store.RunRepository runs;
    private final ApprovalRepository approvals;
    private final RefundStateMachine machine;
    private final StateMachineProperties properties;
    private final Clock clock;

    public Sweepers(io.github.vuppalapatisn.agentic.statemachine.store.RunRepository runs,
                    ApprovalRepository approvals,
                    RefundStateMachine machine,
                    StateMachineProperties properties,
                    Clock clock) {
        this.runs = runs;
        this.approvals = approvals;
        this.machine = machine;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return the number of approvals expired */
    @Scheduled(fixedDelayString = "${agentic.state-machine.sweep-interval:PT1M}")
    public int expireStaleApprovals() {
        List<ApprovalRepository.Approval> expired = approvals.findExpired(clock.instant());
        expired.forEach(machine::expire);
        if (!expired.isEmpty()) {
            log.info("expired {} unanswered approval(s)", expired.size());
        }
        return expired.size();
    }

    /** @return the number of runs reconciled */
    @Scheduled(fixedDelayString = "${agentic.state-machine.sweep-interval:PT1M}")
    public int reconcileStalePayouts() {
        List<RefundRun> stale = runs.findStalePayouts(
                clock.instant().minus(properties.reconcileAfter()));
        stale.forEach(machine::reconcile);
        if (!stale.isEmpty()) {
            log.warn("reconciled {} run(s) stuck in PAYOUT_PENDING", stale.size());
        }
        return stale.size();
    }
}
