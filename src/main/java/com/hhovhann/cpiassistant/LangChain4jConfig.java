package com.hhovhann.cpiassistant;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * LangChain4j wiring, built by hand.
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
     * No timeouts here: they would be ignored. OpenAiChatModel and
     * OpenAiEmbeddingModel always pass their own timeout to the HTTP client,
     * 60 seconds unless .timeout(...) is set on the model, and it overrides
     * whatever this builder says (langchain4j-open-ai 1.20, OpenAiChatModel
     * lines 78-79). A 3-minute read timeout lived here for months and never
     * applied. Timeouts are set on each model below instead.
     */
    @Bean
    HttpClientBuilder langChain4jHttpClientBuilder() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1));
    }

    /**
     * The one chat model everything uses — /chat, /ask and the evaluations.
     * {@code cpi.chat.provider} picks which; see {@link ChatProperties}.
     */
    @Bean
    ChatModel chatModel(HttpClientBuilder httpClientBuilder, ChatProperties chat) {
        return switch (chat.provider()) {
            case LMSTUDIO -> openAiCompatibleChatModel(httpClientBuilder, chat.lmstudio(), chat);
            case OPENAI -> {
                requireKey(chat.openai().apiKey(), "OPENAI_API_KEY");
                yield openAiCompatibleChatModel(httpClientBuilder, chat.openai(), chat);
            }
            case ANTHROPIC -> claudeChatModel(chat.anthropic(), chat);
        };
    }

    /**
     * LM Studio or OpenAI itself: the same client, pointed at a different URL.
     */
    static ChatModel openAiCompatibleChatModel(HttpClientBuilder httpClientBuilder,
                                               ChatProperties.OpenAiCompatible model,
                                               ChatProperties chat) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(model.baseUrl())
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .temperature(model.temperature())
                // Generous: the first request makes LM Studio load the model,
                // and a cold 16K-token prompt takes over a minute to read.
                .timeout(chat.timeout())
                .maxRetries(chat.maxRetries())
                .logRequests(chat.logRequests())
                .logResponses(chat.logResponses())
                .build();
    }

    /**
     * Claude. Only generation moves — retrieval still embeds with LM Studio,
     * because Anthropic has no embedding endpoint.
     *
     * No temperature: Opus 5.5 rejects sampling parameters with a 400. It
     * always thinks, and the thinking counts toward maxTokens.
     */
    static ChatModel claudeChatModel(ChatProperties.Anthropic model, ChatProperties chat) {
        requireKey(model.apiKey(), "ANTHROPIC_API_KEY");
        return AnthropicChatModel.builder()
                .apiKey(model.apiKey())
                .modelName(model.modelName())
                .maxTokens(model.maxTokens())
                .timeout(chat.timeout())
                .maxRetries(chat.maxRetries())
                .logRequests(chat.logRequests())
                .logResponses(chat.logResponses())
                .build();
    }

    /**
     * Fails at startup, with the fix in the message, instead of on the first
     * question with a 401 from the provider.
     */
    private static void requireKey(String apiKey, String envVariable) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("This chat provider needs an API key: export " + envVariable);
        }
    }

    /**
     * Upper bound on tool-calling round trips per question: model replies that
     * ask for tools. Not individual calls — one reply may ask for several.
     * A model that keeps searching without converging costs a round trip, and
     * the whole conversation so far in input tokens, every time; past this
     * limit the question fails instead. LangChain4j's default is 100.
     */
    static final int MAX_TOOL_ROUND_TRIPS = 5;

    /**
     * The assistant: AiServices implements {@link CpiAgent} and runs the tool
     * loop against whichever chat model {@code cpi.chat.provider} picked.
     */
    @Bean
    CpiAgent cpiAgent(ChatModel chatModel, CpiDocsTool cpiDocsTool, CpiTenantTools cpiTenantTools) {
        return AiServices.builder(CpiAgent.class)
                .chatModel(chatModel)
                .tools(cpiDocsTool, cpiTenantTools)
                .maxToolCallingRoundTrips(MAX_TOOL_ROUND_TRIPS)
                .build();
    }

    /**
     * The embedding model. A different model from the chat model, and
     * a different job: it does not generate text, it converts text into a
     * fixed-length vector. LM Studio serves it on the same OpenAI-compatible
     * endpoint, so the only thing that changes is the model name.
     *
     * The same bean must embed both the documents and the questions.
     * Two different embedding models produce vectors in unrelated coordinate
     * systems, and cosine similarity between them is meaningless.
     */
    @Bean
    EmbeddingModel embeddingModel(
            HttpClientBuilder httpClientBuilder,
            @Value("${langchain4j.open-ai.embedding-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.embedding-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.embedding-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.embedding-model.timeout:PT3M}") Duration timeout) {
        return OpenAiEmbeddingModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .logRequests(logRequests)
                .build();
    }

    /**
     * The vector store; {@code cpi.store.type} picks which.
     *
     * InMemoryEmbeddingStore keeps every vector in a list and, on search,
     * compares the query against all of them one by one — at 207 segments that
     * is nothing, but it forgets everything on shutdown. PgVectorEmbeddingStore
     * keeps them in a Postgres table; retrieval and ingestion see the same
     * EmbeddingStore interface and do not change.
     *
     * Both report scores as (cosine + 1) / 2, so cpi.retrieval.min-score means
     * the same with either store (PgVectorStoreTest checks it).
     */
    @Bean
    EmbeddingStore<TextSegment> embeddingStore(StoreProperties store) {
        return switch (store.type()) {
            case MEMORY -> new InMemoryEmbeddingStore<>();
            case PGVECTOR -> pgVectorStore(store.pgvector());
        };
    }

    /**
     * Creates the vector extension and the table on first use. No index: at a
     * few hundred rows an exact scan is fast and never misses a neighbour,
     * which an approximate IVFFlat index can.
     */
    static EmbeddingStore<TextSegment> pgVectorStore(StoreProperties.PgVector pg) {
        return PgVectorEmbeddingStore.builder()
                .host(pg.host())
                .port(pg.port())
                .database(pg.database())
                .user(pg.user())
                .password(pg.password())
                .table(pg.table())
                .dimension(pg.dimension())
                .createTable(true)
                .build();
    }
}
