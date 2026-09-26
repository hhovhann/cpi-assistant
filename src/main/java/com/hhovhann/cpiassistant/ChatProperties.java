package com.hhovhann.cpiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Which chat model answers, bound from {@code cpi.chat.*}.
 *
 * One switch, {@code provider}, picks the model for the whole application;
 * each provider keeps its own settings below it, so switching is a single
 * property:
 *
 * <pre>
 * ./gradlew bootRun --args="--cpi.chat.provider=anthropic"
 * </pre>
 *
 * Only the chat model moves. Embeddings stay on LM Studio whatever the
 * provider: the stored vectors were made by that model, and a question must be
 * embedded by the same one.
 *
 * @param provider     which of the three answers
 * @param timeout      per call; the first local call also loads the model
 * @param maxRetries   null keeps LangChain4j's default: 2 retries, with back-off
 * @param logRequests  log every prompt sent
 * @param logResponses log every answer received
 */
@ConfigurationProperties("cpi.chat")
public record ChatProperties(
        @DefaultValue("lmstudio") Provider provider,
        @DefaultValue("3m") Duration timeout,
        Integer maxRetries,
        boolean logRequests,
        boolean logResponses,
        @DefaultValue OpenAiCompatible lmstudio,
        @DefaultValue OpenAiCompatible openai,
        @DefaultValue Anthropic anthropic) {

    public enum Provider { LMSTUDIO, OPENAI, ANTHROPIC }

    /**
     * LM Studio speaks the OpenAI API, so both use the same client and the
     * same settings; only the URL, key and model differ.
     *
     * @param temperature null sends none, so the model's default applies —
     *                    needed for models that reject any other value
     */
    public record OpenAiCompatible(String baseUrl, String apiKey, String modelName, Double temperature) {
    }

    /**
     * Claude. No temperature: current Claude models reject sampling
     * parameters with a 400.
     *
     * @param maxTokens thinking counts toward this limit, so it is sized for
     *                  thinking plus the answer, not the answer alone
     */
    public record Anthropic(String apiKey, String modelName, @DefaultValue("16000") int maxTokens) {
    }
}
