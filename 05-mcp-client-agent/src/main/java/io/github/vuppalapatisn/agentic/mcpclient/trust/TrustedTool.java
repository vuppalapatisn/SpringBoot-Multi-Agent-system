package io.github.vuppalapatisn.agentic.mcpclient.trust;

/**
 * One entry in the client's allowlist: a remote tool we have reviewed, from a named server, pinned
 * to the definition we reviewed.
 *
 * @param server        which MCP connection may offer it — a tool name alone is not an identity,
 *                      because two servers can both publish {@code issueRefund}
 * @param name          tool name as published
 * @param expectedClass <b>our</b> boundary classification, not the server's hint
 * @param fingerprint   hash of name + description + input schema at review time
 * @param reviewedBy    who reviewed it, so a stale pin has an owner
 */
public record TrustedTool(
        String server,
        String name,
        BoundaryClass expectedClass,
        String fingerprint,
        String reviewedBy) {

    /** The same seven classes as project 02 — remote tools are classified the same way. */
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
}
