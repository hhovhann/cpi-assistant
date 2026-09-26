package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assistant's tool loop without an LLM. A scripted fake model plays the
 * model's part, so these check the wiring: the passages arrive in the message,
 * the tools are offered, a tool runs with the model's arguments and its result
 * goes back, and the round-trip limit holds.
 */
class CpiAgentTest {

    static final String JDBC = "[JDBC Receiver Adapter]\nAdd a receiver channel and select JDBC.";

    /** Knowledge that always returns one JDBC passage, or nothing, and records each query. */
    static final class FakeKnowledge extends KnowledgeService {
        final List<String> queries = new ArrayList<>();
        private final boolean empty;

        FakeKnowledge(boolean empty) {
            super(null, null, null, null, null, 0.82, 1, Duration.ofDays(30), null);
            this.empty = empty;
        }

        @Override
        public Found find(String query) {
            queries.add(query);
            return new Found(empty ? List.of() : List.of(jdbcMatch()), List.of("Database: fake"), List.of());
        }

        static EmbeddingMatch<TextSegment> jdbcMatch() {
            return new EmbeddingMatch<>(0.87, "jdbc#0", null, TextSegment.from("Add a receiver channel and select JDBC.",
                    Metadata.from(TITLE, "JDBC Receiver Adapter").put(URL, SapHelpCatalog.REPO_BLOB + "docs/x/jdbc-receiver-adapter-88be644.md")));
        }
    }

    /** Answers each request with whatever the script says, given the request number. */
    static final class ScriptedChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final Function<Integer, AiMessage> script;

        ScriptedChatModel(Function<Integer, AiMessage> script) {
            this.script = script;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(script.apply(requests.size())).build();
        }
    }

    static AiMessage callTool(String name, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build());
    }

    static CpiAgent agent(ChatModel model, KnowledgeService knowledge) {
        // The tenant tools are offered but never called in these tests.
        return new LangChain4jConfig().cpiAgent(model, new CpiDocsTool(knowledge),
                new CpiTenantTools(new CpiTenantClient("http://localhost:1/unused")));
    }

    @Test
    void aDocsQuestionIsAnsweredFromThePassagesWithoutATool() {
        var knowledge = new FakeKnowledge(false);
        var model = new ScriptedChatModel(n -> AiMessage.from("Use the JDBC adapter [JDBC Receiver Adapter]."));

        Result<String> result = agent(model, knowledge).answer(JDBC, "How do I connect to a database?");

        assertThat(result.toolExecutions()).isEmpty();
        assertThat(model.requests).hasSize(1);
        assertThat(model.requests.getFirst().messages()).last()
                .isInstanceOfSatisfying(UserMessage.class, message -> assertThat(message.singleText())
                        .contains("[JDBC Receiver Adapter]", "Question: How do I connect to a database?"));
        assertThat(model.requests.getFirst().toolSpecifications()).extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("searchDocs", "listIflows", "getProblemMessages", "getErrorDetails");
    }

    @Test
    void theModelCanAskForMoreDocsAndGetsThemBack() {
        var knowledge = new FakeKnowledge(false);
        var model = new ScriptedChatModel(n -> n == 1
                ? callTool("searchDocs", "{\"query\": \"JdbcAdapterException connection pool\"}")
                : AiMessage.from("A pool timeout [JDBC Receiver Adapter]."));

        Result<String> result = agent(model, knowledge).answer("(no documentation found)", "Why did Order_Sync fail?");

        assertThat(knowledge.queries).containsExactly("JdbcAdapterException connection pool");
        assertThat(model.requests.get(1).messages()).last()
                .isInstanceOfSatisfying(ToolExecutionResultMessage.class, message -> assertThat(message.text())
                        .startsWith("[JDBC Receiver Adapter]\n"));
        assertThat(result.content()).isEqualTo("A pool timeout [JDBC Receiver Adapter].");
    }

    @Test
    void aSearchThatFindsNothingSaysSo() {
        var model = new ScriptedChatModel(n -> n == 1 ? callTool("searchDocs", "{\"query\": \"JVM tuning\"}") : AiMessage.from("I don't know."));

        Result<String> result = agent(model, new FakeKnowledge(true)).answer("(no documentation found)", "How do I tune the JVM?");

        assertThat(result.toolExecutions().getFirst().result()).isEqualTo("(no documentation found)");
    }

    @Test
    void stopsAModelThatNeverStopsCallingTools() {
        var knowledge = new FakeKnowledge(false);
        var model = new ScriptedChatModel(n -> callTool("searchDocs", "{\"query\": \"attempt " + n + "\"}"));

        assertThatThrownBy(() -> agent(model, knowledge).answer(JDBC, "anything")).isInstanceOf(RuntimeException.class);
        // One search per reply here, so round trips = searches.
        assertThat(knowledge.queries).hasSizeLessThanOrEqualTo(LangChain4jConfig.MAX_TOOL_ROUND_TRIPS);
    }
}
