package io.github.vuppalapatisn.agentic.agentloop.budget;

import io.github.vuppalapatisn.agentic.agentloop.config.AgentProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run's budget ledger. Mutable, single-run, and consulted before every step and every tool call.
 *
 * <p>This class is what separates an agent loop you can ship from a demo. The loop itself is four
 * lines of Spring AI; the seven counters below are the part that decides whether a bad
 * classification costs you one wasted call or two hundred.
 *
 * <p>Not thread-safe by design: one budget belongs to one run, and a run is single-threaded. Making
 * it concurrent would invite sharing it across runs, which would make every limit meaningless.
 */
public class RunBudget {

    /** One recorded step, for the trace. */
    public record Step(int index, String kind, String detail, Instant at) {
    }

    private final String runId;
    private final AgentProperties limits;
    private final Clock clock;
    private final Instant startedAt;

    private final List<Step> steps = new ArrayList<>();
    private final Map<String, Integer> callsPerTool = new LinkedHashMap<>();

    private int modelTurns;
    private int toolCalls;
    private int promptTokens;
    private int completionTokens;
    private int identicalStreak;
    private String lastToolSignature;

    public RunBudget(String runId, AgentProperties limits, Clock clock) {
        this.runId = runId;
        this.limits = limits;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    // ------------------------------------------------------------ model turns

    /** Called before each model turn. Checks the budgets that a turn can exhaust. */
    public void beginStep() {
        assertWallClock();
        if (modelTurns >= limits.maxSteps()) {
            throw new BudgetExceededException(Budget.STEPS,
                    "reached the %d-step ceiling".formatted(limits.maxSteps()));
        }
        modelTurns++;
        record("LLM", "model turn " + modelTurns);
    }

    /** Called after each model turn, with the usage it reported. */
    public void recordUsage(Integer prompt, Integer completion) {
        promptTokens += prompt == null ? 0 : prompt;
        completionTokens += completion == null ? 0 : completion;
        if (totalTokens() > limits.maxTokens()) {
            throw new BudgetExceededException(Budget.TOKENS,
                    "used %d of %d tokens".formatted(totalTokens(), limits.maxTokens()));
        }
        if (estimatedCostMinor() > limits.maxCostMinor()) {
            throw new BudgetExceededException(Budget.COST,
                    "estimated cost %d exceeds the %d ceiling"
                            .formatted(estimatedCostMinor(), limits.maxCostMinor()));
        }
    }

    // ------------------------------------------------------------ tool calls

    /**
     * Called before every tool call.
     *
     * @param signature tool name plus its arguments — the basis of loop detection
     */
    public void beforeToolCall(String tool, String signature) {
        assertWallClock();

        if (++toolCalls > limits.maxToolCalls()) {
            throw new BudgetExceededException(Budget.TOTAL_TOOL_CALLS,
                    "reached the %d tool-call ceiling".formatted(limits.maxToolCalls()));
        }

        int perTool = callsPerTool.merge(tool, 1, Integer::sum);
        int perToolCeiling = limits.maxCallsPerTool().getOrDefault(tool, limits.maxCallsPerToolDefault());
        if (perTool > perToolCeiling) {
            throw new BudgetExceededException(Budget.PER_TOOL_CALLS,
                    "tool '%s' called %d times, ceiling %d".formatted(tool, perTool, perToolCeiling));
        }

        // Loop detection. `maxNoProgressSteps` is the number of *identical consecutive* calls
        // allowed, counting the first: at 2, a call repeated once more ends the run. A model
        // repeating itself is not going to converge by being allowed another attempt.
        if (signature.equals(lastToolSignature)) {
            identicalStreak++;
        }
        else {
            identicalStreak = 1;
            lastToolSignature = signature;
        }
        if (identicalStreak >= limits.maxNoProgressSteps()) {
            throw new BudgetExceededException(Budget.NO_PROGRESS,
                    "called '%s' with identical arguments %d times in a row"
                            .formatted(tool, identicalStreak));
        }

        record("TOOL", tool);
    }

    private void assertWallClock() {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        if (elapsed.compareTo(limits.maxWallClock()) > 0) {
            throw new BudgetExceededException(Budget.WALL_CLOCK,
                    "elapsed %s exceeds %s".formatted(elapsed, limits.maxWallClock()));
        }
    }

    private void record(String kind, String detail) {
        steps.add(new Step(steps.size() + 1, kind, detail, clock.instant()));
    }

    /** Records something that is neither a model turn nor a tool call — a gate, say. */
    public void note(String kind, String detail) {
        record(kind, detail);
    }

    // --------------------------------------------------------------- readouts

    public String runId() {
        return runId;
    }

    public int modelTurns() {
        return modelTurns;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * A rough cost estimate from token counts. Rough is fine: the purpose is a ceiling that trips
     * before a runaway loop becomes an invoice, not accounting.
     */
    public long estimatedCostMinor() {
        return Math.round((promptTokens * limits.promptCostPerMillionMinor()
                + completionTokens * limits.completionCostPerMillionMinor()) / 1_000_000.0);
    }

    public Duration elapsed() {
        return Duration.between(startedAt, clock.instant());
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public Map<String, Integer> callsPerTool() {
        return Map.copyOf(callsPerTool);
    }

    /** A one-line summary for the response and the log. */
    public String summary() {
        return "steps=%d tools=%d tokens=%d cost=%d elapsed=%s"
                .formatted(modelTurns, toolCalls, totalTokens(), estimatedCostMinor(), elapsed());
    }
}
