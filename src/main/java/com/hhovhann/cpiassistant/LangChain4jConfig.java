package com.hhovhann.cpiassistant;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Track A wiring — LangChain4j, built by hand.
 *
 * We do NOT use langchain4j-spring-boot-starter: it is compiled against
 * Spring Boot 3.5 and fails on Boot 4 (RestClientAutoConfiguration moved out
 * of spring-boot-autoconfigure). The core artifacts have no Spring dependency,
 * so the model is assembled here instead — which is the point of the project:
 * nothing about the client is hidden behind auto-configuration.
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    ChatModel langChain4jChatModel(
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.chat-model.log-responses:false}") boolean logResponses) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
