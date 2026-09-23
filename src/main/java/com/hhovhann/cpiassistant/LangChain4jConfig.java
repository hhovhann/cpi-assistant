package com.hhovhann.cpiassistant;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

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

    /**
     * Forces HTTP/1.1 for every LangChain4j call.
     *
     * Without this, nothing works against LM Studio and the failure is silent:
     * requests hang until the read timeout and surface as
     * "TimeoutException: request timed out", which points at the model being
     * slow rather than at the transport.
     *
     * Cause: dropping langchain4j-spring-boot-starter for Boot 4 also dropped
     * the Spring RestClient transport, so LangChain4j falls back to
     * langchain4j-http-client-jdk. The JDK HttpClient defaults to
     * HttpClient.Version.HTTP_2, which on a cleartext http:// URL sends an h2c
     * upgrade request. LM Studio never answers that upgrade — verified with
     * curl: --http1.1 returns 200 in 2ms, --http2 hangs until the timeout.
     *
     * The read timeout is generous because the first request makes LM Studio
     * load the model into memory, which can take far longer than a warm call.
     */
    @Bean
    HttpClientBuilder langChain4jHttpClientBuilder() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(3));
    }

    @Bean
    ChatModel langChain4jChatModel(
            HttpClientBuilder httpClientBuilder,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.chat-model.log-responses:false}") boolean logResponses) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    /**
     * Step 5 — the embedding model. A different model from the chat model, and
     * a different job: it does not generate text, it converts text into a
     * fixed-length vector. LM Studio serves it on the same OpenAI-compatible
     * endpoint, so the only thing that changes is the model name.
     *
     * The same bean must embed both the documents and, later, the questions.
     * Two different embedding models produce vectors in unrelated coordinate
     * systems, and cosine similarity between them is meaningless.
     */
    @Bean
    EmbeddingModel embeddingModel(
            HttpClientBuilder httpClientBuilder,
            @Value("${langchain4j.open-ai.embedding-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.embedding-model.log-requests:false}") boolean logRequests) {
        return OpenAiEmbeddingModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .build();
    }

    /**
     * Step 5 — the vector store, in memory for now.
     *
     * InMemoryEmbeddingStore keeps every vector in a list and, on search,
     * compares the query against all of them one by one. At 207 segments that
     * is nothing. Step 7 swaps this for pgvector, which is the same interface
     * with an index behind it — the rest of the code will not change.
     */
    @Bean
    EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }
}
