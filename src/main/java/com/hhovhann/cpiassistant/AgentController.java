package com.hhovhann.cpiassistant;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /agent} — the question goes to {@link CpiAgent}, which decides
 * for itself whether and how to search. The response lists every tool call,
 * so you can see what the model decided, not only what it answered.
 */
@RestController
public class AgentController {

    private final CpiAgent agent;

    public AgentController(CpiAgent agent) {
        this.agent = agent;
    }

    @GetMapping("/agent")
    public AgentAnswer agent(@RequestParam String question) {
        long start = System.currentTimeMillis();
        Result<String> result = agent.answer(question);
        return AgentAnswer.from(result, System.currentTimeMillis() - start);
    }

    /**
     * @param toolCalls   in the order the model made them; empty means it
     *                    answered without searching
     * @param inputTokens summed over every model call in the loop — each round
     *                    trip resends the whole conversation so far
     */
    public record AgentAnswer(String answer,
                              List<ToolCall> toolCalls,
                              Integer inputTokens,
                              Integer outputTokens,
                              long millis) {

        static AgentAnswer from(Result<String> result, long millis) {
            TokenUsage usage = result.tokenUsage();
            return new AgentAnswer(
                    result.content(),
                    result.toolExecutions().stream().map(ToolCall::from).toList(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount(),
                    millis);
        }
    }

    /** One tool call: what the model asked for and what it got back. */
    public record ToolCall(String tool, String arguments, String result) {

        static ToolCall from(ToolExecution execution) {
            return new ToolCall(execution.request().name(), execution.request().arguments(), execution.result());
        }
    }
}
