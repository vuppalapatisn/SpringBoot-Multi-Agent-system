package io.github.vuppalapatisn.agentic.statemachine.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashes for idempotency keys and frozen approval payloads.
 *
 * <p>Both are built from durable facts joined with {@code |}, and never from model output: a key
 * that changes between attempts defeats idempotency, and a payload hash that is not stable cannot
 * prove that what executed is what was approved.
 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** The idempotency key for an effect on one business object. */
    public static String idempotencyKey(String runId, String effect, String businessKey, long amountMinor) {
        return sha256(String.join("|", runId, effect, businessKey, Long.toString(amountMinor)));
    }

    /** The frozen payload hash an approver implicitly signs. */
    public static String payloadHash(String runId, String businessKey, long amountMinor) {
        return sha256(String.join("|", runId, businessKey, Long.toString(amountMinor)));
    }
}
