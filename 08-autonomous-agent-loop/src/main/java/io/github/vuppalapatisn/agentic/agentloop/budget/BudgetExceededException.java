package io.github.vuppalapatisn.agentic.agentloop.budget;

/**
 * Thrown the moment a budget is exhausted.
 *
 * <p>It is an exception rather than a return value on purpose: exhaustion must stop the loop
 * wherever it is, and an agent that can choose to ignore a budget does not have one. The service
 * catches it once, at the top, and converts it into an escalation — which is the <b>fail-closed</b>
 * requirement: running out of budget for checking never means doing the risky thing anyway.
 */
public class BudgetExceededException extends RuntimeException {

    private final Budget budget;
    private final String detail;

    public BudgetExceededException(Budget budget, String detail) {
        super("budget " + budget + " exhausted: " + detail);
        this.budget = budget;
        this.detail = detail;
    }

    public Budget budget() {
        return budget;
    }

    public String detail() {
        return detail;
    }
}
