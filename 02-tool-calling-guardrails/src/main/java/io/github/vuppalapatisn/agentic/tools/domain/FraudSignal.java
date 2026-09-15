package io.github.vuppalapatisn.agentic.tools.domain;

/**
 * The <b>only</b> shape in which an external fraud provider's answer is allowed to travel inside
 * this application.
 *
 * <p>This is the taint validator's output type. The provider returns a JSON body we do not control;
 * {@code FraudService} maps it onto this closed set and discards everything else — free-text
 * "reasons", suggested actions, embedded instructions. An {@code R1} response that cannot be mapped
 * becomes {@link #UNAVAILABLE}, which the policy gate treats as not-low risk.
 *
 * <p>The general rule: cross an {@code R1} boundary into an <b>enum</b>, not into a string.
 */
public enum FraudSignal {

    CLEAN(RiskLevel.LOW),
    WATCHLIST(RiskLevel.MEDIUM),
    VELOCITY_ABUSE(RiskLevel.HIGH),
    CHARGEBACK_HISTORY(RiskLevel.HIGH),
    UNAVAILABLE(RiskLevel.MEDIUM);

    private final RiskLevel risk;

    FraudSignal(RiskLevel risk) {
        this.risk = risk;
    }

    public RiskLevel risk() {
        return risk;
    }
}
