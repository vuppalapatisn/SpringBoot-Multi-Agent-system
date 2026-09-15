package io.github.vuppalapatisn.agentic.tools.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical hash of an approval payload — the mechanism that makes an approval <b>frozen</b>.
 *
 * <p>The approver approves a hash. At execution time the arguments are hashed again and compared.
 * If they differ, execution is refused. That closes the most dangerous hole in human-in-the-loop
 * agentic systems: approver sees "refund $24.00", the model is re-invoked on resume, and the
 * executed amount is $2,400.
 *
 * <p>Canonicalisation matters: keys are sorted so that a map iteration order change cannot look
 * like tampering.
 */
public final class PayloadHash {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PayloadHash() {
    }

    public static String of(Map<String, ?> payload) {
        return sha256(canonicalJson(payload));
    }

    public static String canonicalJson(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(payload));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("payload is not serialisable: " + ex.getMessage(), ex);
        }
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
}
