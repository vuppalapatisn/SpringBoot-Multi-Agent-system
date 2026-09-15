package io.github.vuppalapatisn.agentic.tools.gate;

/**
 * System-derived idempotency keys.
 *
 * <p>Three rules, all of them load-bearing:
 *
 * <ol>
 *   <li><b>The system derives the key, never the model.</b> A model-generated key changes between
 *       retries, which defeats the entire mechanism and produces duplicate payments.</li>
 *   <li><b>It is stable across retries and resumes</b>, because it is built only from durable
 *       facts: the run id, the tool, the business key and the amount.</li>
 *   <li><b>It is passed downstream</b> as the provider's own idempotency key, so a network retry
 *       we never observed cannot double-charge.</li>
 * </ol>
 */
public final class IdempotencyKey {

    private IdempotencyKey() {
    }

    public static String of(String runId, String toolName, String businessKey, long amountMinor) {
        return PayloadHash.sha256(String.join("|", runId, toolName, businessKey, Long.toString(amountMinor)));
    }
}
