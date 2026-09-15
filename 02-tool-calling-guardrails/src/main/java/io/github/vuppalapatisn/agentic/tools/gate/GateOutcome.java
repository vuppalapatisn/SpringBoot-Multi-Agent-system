package io.github.vuppalapatisn.agentic.tools.gate;

/** What the policy gate decided. Exhaustive by design so no path can be forgotten. */
public enum GateOutcome {

    /** A deterministic rule fully decides it. No human is interrupted. */
    AUTO,

    /** One or two humans must approve the frozen payload before the effect runs. */
    NEEDS_APPROVAL,

    /**
     * Refused outright. Note that a denial is <b>not</b> an escalation: escalating things a rule
     * already forbids is how approvers are trained to rubber-stamp.
     */
    DENY
}
