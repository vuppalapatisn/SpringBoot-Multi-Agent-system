package io.github.vuppalapatisn.agentic.foundation.audit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Append-only decision log.
 *
 * <p>In-memory here so the project runs with no infrastructure. In production this is a table with
 * no {@code UPDATE} or {@code DELETE} grant to the application role — see project 07 for the
 * persisted version. "Append-only" that the application can rewrite is not append-only.
 */
@Component
public class DecisionLog {

    private final Map<String, List<DecisionRecord>> byRun = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    public DecisionRecord append(DecisionRecord partial) {
        int seq = sequences.computeIfAbsent(partial.runId(), k -> new AtomicInteger()).incrementAndGet();
        DecisionRecord stored = new DecisionRecord(
                partial.runId(), seq, partial.step(), partial.model(), partial.promptHash(),
                partial.structuredOutput(), partial.promptTokens(), partial.completionTokens(),
                partial.finishReason(), partial.took(), partial.at());
        byRun.computeIfAbsent(partial.runId(), k -> new CopyOnWriteArrayList<>()).add(stored);
        return stored;
    }

    public List<DecisionRecord> forRun(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    public int size() {
        return byRun.values().stream().mapToInt(List::size).sum();
    }
}
