package io.github.vuppalapatisn.agentic.tools.boundary;

/**
 * The seven tool boundary classes. See {@code docs/03-TOOL-BOUNDARIES.md} at the repository root.
 *
 * <p>The class is the input to every control in this project: what must be validated, what needs an
 * idempotency key, and what may not be called without an approval.
 */
public enum BoundaryClass {

    /** Read, internal. Own database, cache, config. */
    R0(false, false),

    /**
     * Read, external. Web fetch, partner API, another agent's output, an MCP tool result.
     * The result is <b>tainted input</b>: data, never instruction.
     */
    R1(false, true),

    /** Write, internal, automatically reversible. Drafts, status flags, tags. */
    W1(true, false),

    /** Write, internal, irreversible. Hard delete, ledger post, migration. */
    W2(true, false),

    /** Egress, external, reversible with cooperation. Update a draft, patch a partner record. */
    E1(true, false),

    /** Egress, external, irreversible. Payment, email, publish, provision. */
    E2(true, false),

    /** Privilege change. IAM, secrets, flags, quotas. Never inside the agent's own scope. */
    P1(true, false);

    private final boolean effectful;
    private final boolean tainting;

    BoundaryClass(boolean effectful, boolean tainting) {
        this.effectful = effectful;
        this.tainting = tainting;
    }

    /** True for every class that changes the world and therefore needs an idempotency key. */
    public boolean effectful() {
        return effectful;
    }

    /** True when the tool's <i>result</i> is untrusted content that must be validated. */
    public boolean tainting() {
        return tainting;
    }
}
