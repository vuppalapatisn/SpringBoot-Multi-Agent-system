package io.github.vuppalapatisn.agentic.tools.gate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the guard needs to decide whether an effect may run.
 *
 * @param runId        correlation id; part of every idempotency key
 * @param toolName     used to resolve the boundary descriptor
 * @param businessKey  the thing being acted on (an order id), not a value
 * @param amountMinor  the authoritative amount, read from trusted data
 * @param payload      the exact arguments — what gets hashed and frozen
 * @param approvalToken the approval that authorises this call, or {@code null}
 */
public record EffectContext(
        String runId,
        String toolName,
        String businessKey,
        long amountMinor,
        Map<String, Object> payload,
        String approvalToken) {

    public static EffectContext of(String runId, String toolName, String businessKey, long amountMinor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("businessKey", businessKey);
        payload.put("amountMinor", amountMinor);
        return new EffectContext(runId, toolName, businessKey, amountMinor, payload, null);
    }

    public EffectContext with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.put(key, value);
        return new EffectContext(runId, toolName, businessKey, amountMinor, merged, approvalToken);
    }

    public EffectContext withToken(String token) {
        return new EffectContext(runId, toolName, businessKey, amountMinor, payload, token);
    }

    public String payloadHash() {
        return PayloadHash.of(payload);
    }

    public String idempotencyKey() {
        return IdempotencyKey.of(runId, toolName, businessKey, amountMinor);
    }
}
