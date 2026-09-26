package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tool loop without an LLM. A scripted fake model plays the model's part —
 * "call searchCpiDocs", then "answer" — so these check our wiring: the tool is
 * offered, runs with the model's arguments, its result goes back to the model,
 * and the round-trip limit holds.
 */
class CpiAgentTest {

    private static final String QUESTION = "How do I connect to a database from an iFlow?";

    /** Records every query and returns one JDBC passage, or nothing. */
    private static final class FakeRetrieval extends RetrievalService {
        final List<String> queries = new ArrayList<>();
        private final boolean empty;

        FakeRetrieval(boolean empty) {
            super(null, null, "", 0.80);
            this.empty = empty;
        }

        @Override
        public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
            queries.add(query);
            return empty ? List.of() : List.of(new EmbeddingMatch<>(0.86, "jdbc#0", null,
                    TextSegment.from("Add a receiver channel and select JDBC.",
                            Metadata.from("file_name", "02-jdbc-adapter.txt"))));
        }
    }

    /** Answers each request with whatever the script says, given the request number. */
    private static final class ScriptedChatModel implements ChatModel {
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

    private static AiMessage callSearch(String query) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1").name("searchCpiDocs").arguments("{\"query\": \"" + query + "\"}").build());
    }

    private static CpiAgent agent(ChatModel model, RetrievalService retrieval) {
        // The tenant tools are offered but never called in these tests.
        return new LangChain4jConfig().cpiAgent(model, new CpiDocsTool(retrieval),
                new CpiTenantTools(new CpiTenantClient("http://localhost:1/unused")));
    }

    @Test
    void runsTheToolTheModelAsksForAndSendsTheResultBack() {
        var retrieval = new FakeRetrieval(false);
        var model = new ScriptedChatModel(n -> n == 1
                ? callSearch("JDBC receiver adapter")
                : AiMessage.from("Use the JDBC adapter [02-jdbc-adapter.txt]."));

        Result<String> result = agent(model, retrieval).answer(QUESTION);

        assertThat(result.content()).isEqualTo("Use the JDBC adapter [02-jdbc-adapter.txt].");
        // The model chose the query, not the user's wording.
        assertThat(retrieval.queries).containsExactly("JDBC receiver adapter");
        assertThat(result.toolExecutions()).singleElement()
                .satisfies(e -> assertThat(e.request().name()).isEqualTo("searchCpiDocs"));

        // The first request offered the tool; the second carried its result back.
        assertThat(model.requests).hasSize(2);
        assertThat(model.requests.getFirst().toolSpecifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("searchCpiDocs", "listIflows", "getProblemMessages", "getErrorDetails");
        assertThat(model.requests.get(1).messages()).last()
                .isInstanceOfSatisfying(ToolExecutionResultMessage.class, message -> assertThat(message.text())
                        .contains("[02-jdbc-adapter.txt]", "select JDBC"));
    }

    @Test
    void modelMayAnswerWithoutSearching() {
        var retrieval = new FakeRetrieval(false);
        var model = new ScriptedChatModel(n -> AiMessage.from("That is not a CPI question."));

        Result<String> result = agent(model, retrieval).answer("What is the capital of France?");

        assertThat(result.toolExecutions()).isEmpty();
        assertThat(retrieval.queries).isEmpty();
    }

    @Test
    void emptySearchTellsTheModelSoInsteadOfReturningNothing() {
        var retrieval = new FakeRetrieval(true);
        var model = new ScriptedChatModel(n -> n == 1 ? callSearch("tune JVM garbage collection") : AiMessage.from("I don't know."));

        Result<String> result = agent(model, retrieval).answer("How do I tune JVM garbage collection?");

        assertThat(result.toolExecutions().getFirst().result()).isEqualTo("No relevant passages found in the CPI documentation.");
    }

    @Test
    void stopsAModelThatNeverStopsSearching() {
        var retrieval = new FakeRetrieval(false);
        var model = new ScriptedChatModel(n -> callSearch("JDBC, attempt " + n));

        assertThatThrownBy(() -> agent(model, retrieval).answer(QUESTION))
                .isInstanceOf(RuntimeException.class);
        // One search per reply here, so round trips = searches.
        assertThat(retrieval.queries).hasSizeLessThanOrEqualTo(LangChain4jConfig.MAX_TOOL_ROUND_TRIPS);
    }
}
