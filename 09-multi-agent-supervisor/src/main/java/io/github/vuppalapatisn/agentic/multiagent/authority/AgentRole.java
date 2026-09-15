package io.github.vuppalapatisn.agentic.multiagent.authority;

import java.util.Set;

/**
 * The four roles, and the boundary classes each one may touch.
 *
 * <p><b>This enum is the security model of the whole project.</b> The value of multi-agent design is
 * not extra intelligence — it is separation of authority. Only {@link #PAYOUT} may reach an
 * effectful tool, so a successful prompt injection against {@link #FRAUD} cannot move money,
 * because {@code FRAUD} has no capability that moves money.
 *
 * <p>{@link AgentCapabilities} validates the declared tools of every agent against these sets at
 * startup, so a future change that hands a payment tool to the intake agent fails the application
 * rather than production.
 */
public enum AgentRole {

    /** Reads the untrusted customer message. Holds nothing. The blast radius of a compromise: none. */
    INTAKE(Set.of()),

    /** Reads policy. No external calls, no writes. */
    POLICY(Set.of(BoundaryClass.R0)),

    /** Talks to the fraud provider. May read externally; may not write anything. */
    FRAUD(Set.of(BoundaryClass.R0, BoundaryClass.R1)),

    /** The only role that can change the world — and the only one that can escalate to a human. */
    PAYOUT(Set.of(BoundaryClass.R0, BoundaryClass.W1, BoundaryClass.E2));

    /** The seven classes from {@code docs/03-TOOL-BOUNDARIES.md}. */
    public enum BoundaryClass {
        R0(false), R1(false), W1(true), W2(true), E1(true), E2(true), P1(true);

        private final boolean effectful;

        BoundaryClass(boolean effectful) {
            this.effectful = effectful;
        }

        public boolean effectful() {
            return effectful;
        }
    }

    private final Set<BoundaryClass> allowed;

    AgentRole(Set<BoundaryClass> allowed) {
        this.allowed = allowed;
    }

    public Set<BoundaryClass> allowedClasses() {
        return allowed;
    }

    public boolean may(BoundaryClass boundaryClass) {
        return allowed.contains(boundaryClass);
    }

    /** Only {@link #PAYOUT} may hold an effectful capability. */
    public boolean mayCauseEffects() {
        return allowed.stream().anyMatch(BoundaryClass::effectful);
    }

    /** Only the role that can act may ask a human to authorise acting. */
    public boolean mayEscalateToHuman() {
        return this == PAYOUT;
    }
}
