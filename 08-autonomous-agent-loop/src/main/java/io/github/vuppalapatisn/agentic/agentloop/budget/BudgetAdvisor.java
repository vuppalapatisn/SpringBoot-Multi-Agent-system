package io.github.vuppalapatisn.agentic.agentloop.budget;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Counts model turns and tokens as the loop runs.
 *
 * <p>An advisor is the right tool here, and it is worth being precise about why. An advisor wraps
 * the <b>model call</b>, which is exactly what a step budget measures. Compare with the approval
 * gate, which wraps an <b>effect</b> and therefore must not be an advisor — see
 * {@code gate/GuardedPayout}.
 *
 * <p>Ordering matters: {@code getOrder()} returns a very low value so this advisor sits outside the
 * {@code ToolCallingAdvisor}, and therefore sees every iteration of the tool-calling loop rather
 * than just the first.
 */
public class BudgetAdvisor implements BaseAdvisor {

    /** The budget for the current run, taken from the request context. */
    public static final String RUN_BUDGET = "runBudget";

    private final int order;

    public BudgetAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        budgetOf(request.context().get(RUN_BUDGET)).beginStep();
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        RunBudget budget = budgetOf(response.context().get(RUN_BUDGET));
        Usage usage = response.chatResponse() == null || response.chatResponse().getMetadata() == null
                ? null : response.chatResponse().getMetadata().getUsage();
        budget.recordUsage(
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
        return response;
    }

    private static RunBudget budgetOf(Object value) {
        if (value instanceof RunBudget budget) {
            return budget;
        }
        // Fail closed: an agent loop with no budget attached must not run at all.
        throw new IllegalStateException(
                "no RunBudget in the request context; an unbudgeted agent loop is not allowed");
    }

    @Override
    public String getName() {
        return "budget";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
