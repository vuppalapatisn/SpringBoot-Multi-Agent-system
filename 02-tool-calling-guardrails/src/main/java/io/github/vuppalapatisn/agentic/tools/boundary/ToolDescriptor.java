package io.github.vuppalapatisn.agentic.tools.boundary;

import java.time.Duration;

/**
 * Resolved boundary metadata for one tool, as the guard sees it at call time.
 *
 * @param name               the tool name exposed to the model
 * @param boundaryClass      classification from {@link ToolBoundary}
 * @param irreversible       whether an approval is mandatory
 * @param compensation       compensating operation, or {@code NONE}
 * @param compensationWindow how long compensation stays possible
 * @param maxCallsPerRun     per-run call ceiling enforced by the guard
 */
public record ToolDescriptor(
        String name,
        BoundaryClass boundaryClass,
        boolean irreversible,
        String compensation,
        Duration compensationWindow,
        int maxCallsPerRun) {

    public boolean compensable() {
        return !"NONE".equals(compensation) && !compensationWindow.isZero();
    }

    static ToolDescriptor from(String name, ToolBoundary annotation) {
        return new ToolDescriptor(
                name,
                annotation.value(),
                annotation.irreversible(),
                annotation.compensation(),
                Duration.parse(annotation.compensationWindow()),
                annotation.maxCallsPerRun());
    }
}
