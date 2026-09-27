package com.hhovhann.cpiassistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one path, with a scripted model and canned knowledge: what gets called
 * when, what the answer reports, and the citation check.
 */
class AssistServiceTest {

    private static final String QUESTION = "How do I configure the AS4 receiver adapter?";

    /** A canned result for find(), and a record of the calls. */
    private static class CannedKnowledge extends KnowledgeService {
        final List<String> calls = new ArrayList<>();
        private final Found found;

        CannedKnowledge(Found found) {
            super(null, null, null, null, null, 0.82, Duration.ofDays(30), null);
            this.found = found;
        }

        @Override
        public Found find(String query) {
            calls.add("find");
            return found;
        }
    }

    private static EmbeddingMatch<TextSegment> passage(String title) {
        return new EmbeddingMatch<>(0.86, title, null, TextSegment.from(title + " text.",
                dev.langchain4j.data.document.Metadata.from(KnowledgeService.TITLE, title)
                        .put(KnowledgeService.URL, "https://example.com/" + title.replace(' ', '-'))));
    }

    private static AssistService service(CpiAgentTest.ScriptedChatModel model, KnowledgeService knowledge) {
        return new AssistService(knowledge, CpiAgentTest.agent(model));
    }

    @Test
    void aDatabaseHitIsOneModelCallWithACheckedCitation() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(passage("AS4 Receiver Adapter")),
                List.of("Database: 1 passage(s), best 0.860"), List.of()));
        var model = new CpiAgentTest.ScriptedChatModel(n -> AiMessage.from("Use ebMS 3.0 [AS4 Receiver Adapter]."));

        var answer = service(model, knowledge).assist(QUESTION);

        assertThat(model.requests).hasSize(1);
        assertThat(knowledge.calls).containsExactly("find");
        assertThat(answer.path()).containsExactly("Database: 1 passage(s), best 0.860", "Answered");
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
                List.of("Database: 1 passage(s)"), List.of()));
        var model = new CpiAgentTest.ScriptedChatModel(n ->
                AiMessage.from("Use ebMS [AS4 Receiver Adapter] and TLS [Overview of Integration Flow Editor]."));

        var answer = service(model, knowledge).assist(QUESTION);

        assertThat(answer.unverifiedCitations()).containsExactly("Overview of Integration Flow Editor");
    }

    @Test
    void aPageNamedInsideAPassageIsNotAnInvention() {
        var jdbc = new EmbeddingMatch<>(0.9, "jdbc", null, TextSegment.from(
                "Your administrator deployed the drivers. For more information, see Configure JDBC Drivers.",
                dev.langchain4j.data.document.Metadata.from(KnowledgeService.TITLE, "JDBC Receiver Adapter")));
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(jdbc), List.of("Database: 1 passage(s)"), List.of()));
        var model = new CpiAgentTest.ScriptedChatModel(n -> AiMessage.from(
                "Deploy the drivers, see [Configure JDBC Drivers] [JDBC Receiver Adapter]. Tune [Retry Iterations]."));

        var answer = service(model, knowledge).assist("How do I configure a JDBC adapter?");

        // Named in the passage: fine. Named nowhere: flagged.
        assertThat(answer.unverifiedCitations()).containsExactly("Retry Iterations");
    }

    @Test
    void numbersIdsAndUrlsInBracketsAreNotCitations() {
        assertThat(AssistService.citedTitles("See [1], message [308fd65c82453608a88a13344717584f], "
                + "the blog [https://blogs.sap.com/2021/03/16/kafka-adapter/], "
                + "the expression payload/LogEntry[severity = 'Error'] and items[0], "
                + "[JDBC Receiver Adapter] [Handle Errors Gracefully][Define Router]."))
                .containsExactly("JDBC Receiver Adapter", "Handle Errors Gracefully", "Define Router");
    }

    @Test
    void anEmptyAnswerIsNeverPassedOnAsNull() {
        var knowledge = new CannedKnowledge(new KnowledgeService.Found(List.of(), List.of("Database: nothing above the 0.80 floor"), List.of()));
        var model = new CpiAgentTest.ScriptedChatModel(n -> AiMessage.from(""));

        var answer = service(model, knowledge).assist("How do I configure the Mail adapter?");

        assertThat(answer.answer()).isEqualTo(AssistService.EMPTY);
    }
}
