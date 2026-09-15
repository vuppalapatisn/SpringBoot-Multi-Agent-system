package io.github.vuppalapatisn.agentic.agentloop.config;

import io.github.vuppalapatisn.agentic.agentloop.budget.BudgetAdvisor;
import io.github.vuppalapatisn.agentic.agentloop.budget.BudgetExceededException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The loop's client.
     *
     * <p><b>The advisor order here is load-bearing, and getting it wrong hangs the loop.</b>
     * {@code ToolCallingAdvisor} runs the tool-call loop and only advisors with a <i>higher</i>
     * order participate in each iteration. A budget advisor ordered below it sees exactly one turn
     * per {@code call()}, so {@code maxSteps} never trips and a runaway model loops forever.
     * {@link #BUDGET_ADVISOR_ORDER} is therefore above
     * {@code ToolCallingAdvisor.DEFAULT_ORDER} ({@code HIGHEST_PRECEDENCE + 300}).
     */
    @Bean
    ChatClient agentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(new ClassPathResource("prompts/agent-system.st"))
                .defaultOptions(ChatOptions.builder().temperature(0.0d).maxTokens(1024))
                .defaultAdvisors(
                        new BudgetAdvisor(BUDGET_ADVISOR_ORDER),
                        SimpleLoggerAdvisor.builder().build())
                .build();
    }

    /** Inside the tool-calling loop, so every model turn is counted. */
    static final int BUDGET_ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 400;

    /**
     * Budget exhaustion must <b>stop</b> the loop, not become a message the model can shrug off.
     *
     * <p>By default Spring AI turns a tool exception into text handed back to the model — which is
     * right for a validation error (the model can correct itself and try again) and catastrophic
     * for a budget (the model is told "you are out of budget" and simply asks again, forever).
     * Rethrowing {@link BudgetExceededException} is what makes the ceiling a ceiling.
     */
    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .rethrowExceptions(List.of(BudgetExceededException.class))
                .build();
    }
}
