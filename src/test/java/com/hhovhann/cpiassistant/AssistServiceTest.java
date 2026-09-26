package com.hhovhann.cpiassistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one path, with a scripted model and canned knowledge: what gets called
 * when, what the answer reports, and the citation check.
 */
class AssistServiceTest {

    private static final String QUESTION = "How do I configure the AS4 receiver adapter?";

    /** Canned results for find() and fetchFromSapHelp(), and a record of the calls. */
    private static class CannedKnowledge extends KnowledgeService {
        final List<String> calls = new ArrayList<>();
        private final Found found;
        private final Found fetched;

        CannedKnowledge(Found found, Found fetched) {
            super(null, null, null, null, null, 0.82, 1, Duration.ofDays(30), null);
            this.found = found;
            this.fetched = fetched;
        }

        @Override
        public Found find(String query) {
            calls.add("find");
            return found;
        }

        @Override
        public Found fetchFromSapHelp(String query) {
            calls.add("fetchFromSapHelp");
            return fetched;
        }
    }

    /** Knows one title, to link sources a tool returned. */
    private static final class OneTitleCatalog extends SapHelpCatalog {
        OneTitleCatalog() {
            super(List.of(), null, "", "");
        }

        @Override
        public Optional<Page> pageByTitle(String title) {
            return Optional.of(new Page("docs/x/" + title.toLowerCase().replace(' ', '-') + "-1234567.md", title));
        }
    }

    private static EmbeddingMatch<TextSegment> passage(String title) {
        return new EmbeddingMatch<>(0.86, title, null, TextSegment.from(title + " text.",
                dev.langchain4j.data.document.Metadata.from(KnowledgeService.TITLE, title)
                        .put(KnowledgeService.URL, "https://example.com/" + title.replace(' ', '-'))));
    }

    private static AssistService service(CpiAgentTest.ScriptedChatModel model, KnowledgeService knowledge) {
        return new AssistService(knowledge, CpiAgentTest.agent(model, knowledge), new OneTitleCatalog());
    }

    @Test
    void aDatabaseHitIsOneModelCallWithACheckedCitation() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(passage("AS4 Receiver Adapter")),
                List.of("Database: 1 passage(s), best 0.860"), List.of()), null);
        var model = new CpiAgentTest.ScriptedChatModel(n -> AiMessage.from("Use ebMS 3.0 [AS4 Receiver Adapter]."));

        var answer = service(model, knowledge).assist(QUESTION);

        assertThat(model.requests).hasSize(1);
        assertThat(knowledge.calls).containsExactly("find");
        assertThat(answer.path()).containsExactly("Database: 1 passage(s), best 0.860", "Answered from the passages, no tool");
        assertThat(answer.sources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("AS4 Receiver Adapter");
            assertThat(source.cited()).isTrue();
            assertThat(source.url()).isEqualTo("https://example.com/AS4-Receiver-Adapter");
        });
        assertThat(answer.unverifiedCitations()).isEmpty();
    }

    @Test
    void aCitationOfAPageTheModelNeverSawIsFlagged() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(passage("AS4 Receiver Adapter")),
                List.of("Database: 1 passage(s)"), List.of()), null);
        var model = new CpiAgentTest.ScriptedChatModel(n ->
                AiMessage.from("Use ebMS [AS4 Receiver Adapter] and TLS [Overview of Integration Flow Editor]."));

        var answer = service(model, knowledge).assist(QUESTION);

        assertThat(answer.unverifiedCitations()).containsExactly("Overview of Integration Flow Editor");
    }

    @Test
    void closeButWrongPassagesSendTheQuestionToSapHelpOnce() {
        var as2 = new KnowledgeService.Found(List.of(passage("Configure the AS2 Receiver Adapter")),
                List.of("Database: 1 passage(s), best 0.850"), List.of());
        var as4 = new KnowledgeService.Found(List.of(passage("AS4 Receiver Adapter")),
                List.of("SAP Help: downloaded \"AS4 Receiver Adapter\"", "Database again: 1 passage(s)"), List.of("AS4 Receiver Adapter"));
        var knowledge = new CannedKnowledge(as2, as4);
        var model = new CpiAgentTest.ScriptedChatModel(n -> n == 1
                ? AiMessage.from("I don't know.")
                : AiMessage.from("Use ebMS 3.0 [AS4 Receiver Adapter]."));

        var answer = service(model, knowledge).assist(QUESTION);

        assertThat(knowledge.calls).containsExactly("find", "fetchFromSapHelp");
        assertThat(model.requests).hasSize(2);
        assertThat(answer.answer()).isEqualTo("Use ebMS 3.0 [AS4 Receiver Adapter].");
        assertThat(answer.path()).contains("The passages did not answer it: asking SAP Help", "SAP Help: downloaded \"AS4 Receiver Adapter\"");
        assertThat(answer.sources()).extracting(AssistService.Source::title).containsExactly("AS4 Receiver Adapter");
    }

    @Test
    void iDontKnowWithNothingNewToDownloadKeepsTheAnswer() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(passage("Configure the AS2 Receiver Adapter")),
                List.of("Database: 1 passage(s)"), List.of()),
                new KnowledgeService.Found(List.of(), List.of("SAP Help: no page title matches well enough (needs 0.82)"), List.of()));
        var model = new CpiAgentTest.ScriptedChatModel(n -> AiMessage.from("I don't know."));

        var answer = service(model, knowledge).assist("How do I tune the JVM?");

        assertThat(model.requests).hasSize(1);
        assertThat(answer.answer()).isEqualTo("I don't know.");
    }

    @Test
    void toolCallsAreReportedAndTheirPagesCountAsGiven() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(), List.of("Database: nothing above the 0.80 floor"), List.of()), null) {
            @Override
            public Found find(String query) {
                calls.add("find");
                return query.contains("Order_Sync")
                        ? new Found(List.of(), List.of("Database: nothing above the 0.80 floor"), List.of())
                        : new Found(List.of(passage("JDBC Receiver Adapter")), List.of("Database: 1 passage(s)"), List.of());
            }
        };
        var model = new CpiAgentTest.ScriptedChatModel(n -> n == 1
                ? CpiAgentTest.callTool("searchDocs", "{\"query\": \"JDBC pool timeout\"}")
                : AiMessage.from("A pool timeout [JDBC Receiver Adapter]."));

        var answer = service(model, knowledge).assist("Why did Order_Sync fail today?");

        assertThat(answer.toolCalls()).singleElement().satisfies(call -> assertThat(call.tool()).isEqualTo("searchDocs"));
        assertThat(answer.path()).contains("Tool: searchDocs {\"query\": \"JDBC pool timeout\"}", "Answered");
        assertThat(answer.unverifiedCitations()).isEmpty();
        assertThat(answer.sources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("JDBC Receiver Adapter");
            assertThat(source.cited()).isTrue();
            assertThat(source.url()).endsWith("jdbc-receiver-adapter-1234567.md");
        });
    }

    @Test
    void numbersAndIdsInBracketsAreNotCitations() {
        assertThat(AssistService.citedTitles("See [1], message [308fd65c82453608a88a13344717584f] and [JDBC Receiver Adapter] [Handle Errors Gracefully]."))
                .containsExactly("JDBC Receiver Adapter", "Handle Errors Gracefully");
    }
}
