package io.github.vuppalapatisn.agentic.tools.boundary;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the boundary class of a tool <b>in code</b>, so the runtime can act on it.
 *
 * <p>Documentation drifts; an annotation the guard reads at call time does not. Every
 * {@code @Tool}-annotated method in this project must carry one, and
 * {@link ToolBoundaryValidator} fails application startup if any does not. That is what makes a
 * tool added by a future developer <b>refused by default</b> rather than quietly dangerous.
 *
 * <pre>{@code
 * @Tool(name = "issueRefund", description = "...")
 * @ToolBoundary(value = BoundaryClass.E2, irreversible = true,
 *               compensation = "cancelRefund", compensationWindow = "PT30M")
 * String issueRefund(String orderId) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolBoundary {

    /** The boundary class. */
    BoundaryClass value();

    /**
     * True when no single automated compensating action restores the prior state within SLA,
     * without a human and without third-party cooperation. Be strict: "ops can reverse it" means
     * {@code true}.
     */
    boolean irreversible() default false;

    /** Name of the compensating operation, or {@code NONE}. */
    String compensation() default "NONE";

    /**
     * ISO-8601 duration for which compensation remains possible ({@code PT30M}), or {@code PT0S}
     * when there is no window. Encoded here so a timer can enforce it rather than a comment.
     */
    String compensationWindow() default "PT0S";

    /** Maximum number of calls per run. The framework's tool limits are the belt; this is a brace. */
    int maxCallsPerRun() default 3;
}
