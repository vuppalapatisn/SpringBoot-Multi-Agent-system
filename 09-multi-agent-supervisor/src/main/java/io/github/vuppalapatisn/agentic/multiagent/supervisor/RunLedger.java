package io.github.vuppalapatisn.agentic.multiagent.supervisor;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole;
import io.github.vuppalapatisn.agentic.multiagent.config.MultiAgentProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Budgets and termination rules for a multi-agent run.
 *
 * <p>A multi-agent system has all of a single agent's failure modes plus three of its own, and each
 * one is a rule here:
 *
 * <ol>
 *   <li><b>Unbounded handoffs</b> — a run that keeps delegating. Bounded by
 *       {@code maxHandoffs}.</li>
 *   <li><b>Ping-pong</b> — A hands to B, B hands back to A, forever. Rejected by
 *       {@link #assertNoPingPong}.</li>
 *   <li><b>Per-agent runaway</b> — one specialist burning the whole run's budget. Bounded by
 *       {@code maxModelCallsPerAgent}.</li>
 * </ol>
 *
 * <p>The supervisor in this project is deterministic code, so ping-pong is impossible by
 * construction — the check exists anyway, because the moment somebody replaces the supervisor with
 * a model (the obvious "improvement") it becomes possible, and a control added after the incident
 * is worth less than one that was already there.
 */
public class RunLedger {

    /** Thrown when a multi-agent budget or termination rule is hit. Fails closed to an escalation. */
    public static class TerminationException extends RuntimeException {

        private final String rule;

        public TerminationException(String rule, String message) {
            super(message);
            this.rule = rule;
        }

        public String rule() {
            return rule;
        }
    }

    private final String runId;
    private final MultiAgentProperties limits;
    private final Clock clock;
    private final Instant startedAt;

    private final List<AgentRole> handoffs = new ArrayList<>();
    private final Map<AgentRole, Integer> modelCallsByAgent = new LinkedHashMap<>();

    public RunLedger(String runId, MultiAgentProperties limits, Clock clock) {
        this.runId = runId;
        this.limits = limits;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** Called by the supervisor before delegating to a specialist. */
    public void handoffTo(AgentRole role) {
        assertWallClock();
        if (handoffs.size() >= limits.maxHandoffs()) {
            throw new TerminationException("MAX_HANDOFFS",
                    "reached the %d-handoff ceiling".formatted(limits.maxHandoffs()));
        }
        assertNoPingPong(role);
        handoffs.add(role);
    }

    /**
     * Rejects an A → B → A cycle. The supervisor here cannot produce one; a model-routed supervisor
     * can, which is precisely why the rule is written down rather than assumed.
     */
    private void assertNoPingPong(AgentRole next) {
        if (handoffs.size() >= 2) {
            AgentRole previous = handoffs.get(handoffs.size() - 2);
            AgentRole last = handoffs.get(handoffs.size() - 1);
            if (next == previous && next != last) {
                throw new TerminationException("PING_PONG",
                        "handoff cycle detected: %s -> %s -> %s".formatted(previous, last, next));
            }
        }
    }

    /** Called by each agent before it calls a model. */
    public void beforeModelCall(AgentRole role) {
        assertWallClock();
        int used = modelCallsByAgent.merge(role, 1, Integer::sum);
        if (used > limits.maxModelCallsPerAgent()) {
            throw new TerminationException("MAX_AGENT_CALLS",
                    "agent %s used %d model calls, ceiling %d"
                            .formatted(role, used, limits.maxModelCallsPerAgent()));
        }
        if (totalModelCalls() > limits.maxModelCallsPerRun()) {
            throw new TerminationException("MAX_RUN_CALLS",
                    "run used %d model calls, ceiling %d"
                            .formatted(totalModelCalls(), limits.maxModelCallsPerRun()));
        }
    }

    private void assertWallClock() {
        if (elapsed().compareTo(limits.maxWallClock()) > 0) {
            throw new TerminationException("WALL_CLOCK",
                    "elapsed %s exceeds %s".formatted(elapsed(), limits.maxWallClock()));
        }
    }

    public String runId() {
        return runId;
    }

    public List<AgentRole> handoffs() {
        return List.copyOf(handoffs);
    }

    public List<String> handoffNames() {
        return handoffs.stream().map(AgentRole::name).toList();
    }

    public int totalModelCalls() {
        return modelCallsByAgent.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<AgentRole, Integer> modelCallsByAgent() {
        return Map.copyOf(modelCallsByAgent);
    }

    public Duration elapsed() {
        return Duration.between(startedAt, clock.instant());
    }
}
