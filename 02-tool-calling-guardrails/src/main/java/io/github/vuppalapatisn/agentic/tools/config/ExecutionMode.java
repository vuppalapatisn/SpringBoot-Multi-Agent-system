package io.github.vuppalapatisn.agentic.tools.config;

/**
 * The kill switch, as required by Phase 10 of the checklist.
 *
 * <p>Set with {@code agentic.tools.execution-mode}. It must be changeable without a deploy, and
 * there is a test asserting that {@link #DRY_RUN} performs no writes.
 */
public enum ExecutionMode {

    /** Normal operation: effects happen. */
    EXECUTE,

    /**
     * Validate, record intent, return a synthetic receipt, change nothing. Used by tests, by the
     * plan-preview endpoint, during shadow launch, and by on-call during an incident.
     */
    DRY_RUN,

    /** Refuse every effectful tool with a typed error. */
    DISABLED
}
