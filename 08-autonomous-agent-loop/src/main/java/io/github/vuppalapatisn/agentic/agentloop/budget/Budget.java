package io.github.vuppalapatisn.agentic.agentloop.budget;

/**
 * The budgets an agent loop must have. Every one of them needs a number, an enforcement point, and
 * a fail-closed exhaustion path — Phase 7 of the checklist.
 *
 * <p>They are an enum rather than a set of booleans so that metrics can be tagged with which
 * budget ran out. The distribution across these tags is the single most useful diagnostic an agent
 * loop produces: {@code NO_PROGRESS} spikes mean the model is stuck, {@code TOTAL_TOOL_CALLS}
 * spikes mean the task is harder than the budget assumes, and {@code COST} spikes mean somebody
 * needs to look at the prompt.
 */
public enum Budget {

    /** Model turns. A turn is one round of "decide what to do next". */
    STEPS,

    /** Tool calls across the whole run. */
    TOTAL_TOOL_CALLS,

    /** Tool calls for one particular tool. */
    PER_TOOL_CALLS,

    /** Prompt + completion tokens across the run. Context regrows every step, so this matters. */
    TOKENS,

    /** Estimated spend, in the smallest currency unit. */
    COST,

    /** Wall clock. The budget a human waiting on the answer actually feels. */
    WALL_CLOCK,

    /**
     * Consecutive steps that made no progress — the same tool called with the same arguments.
     * A model that repeats itself is not going to converge by being allowed more attempts.
     */
    NO_PROGRESS
}
