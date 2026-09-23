package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No LLM, no embedding model, no LM Studio. Hand-written fakes stand
 * in for retrieval and the chat model, so these run in milliseconds and test
 * only our code: what goes into the prompt and what comes back out of /ask.
 */
class RagServiceTest {

    private static final String QUESTION = "How do I connect to a database from an iFlow?";

    private static EmbeddingMatch<TextSegment> match(String fileName, String text, double score) {
        return new EmbeddingMatch<>(score, fileName + "#0", null, TextSegment.from(text, Metadata.from("file_name", fileName)));
    }

    private static final List<EmbeddingMatch<TextSegment>> TWO_MATCHES = List.of(
            match("02-jdbc-adapter.txt", "Add a receiver channel and select JDBC.", 0.86),
            match("10-partner-directory.txt", "Partner Directory stores partner-specific values.", 0.85));

    /** Returns canned matches instead of embedding the question. */
    private static RetrievalService retrievalReturning(List<EmbeddingMatch<TextSegment>> matches) {
        return new RetrievalService(null, null, "", 0.80) {
            @Override
            public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
                return matches;
            }
        };
    }

    /** Records every request and answers with a fixed response. */
    private static final class FakeChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final ChatResponse response;

        FakeChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return response;
        }
    }

    private static ChatResponse response(String text, TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(usage).build();
    }

    @Nested
    class BuildPrompt {

        private final RagService service = new RagService(retrievalReturning(List.of()),
                new FakeChatModel(response("unused", null)));

        @Test
        void systemMessageRestrictsTheModelToTheContext() {
            List<ChatMessage> messages = service.buildPrompt(QUESTION, TWO_MATCHES);

            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
            assertThat(((SystemMessage) messages.getFirst()).text())
                    .contains("ONLY from the context")
                    .contains("I don't know");
        }

        @Test
        void userMessageCarriesEveryRetrievedChunkLabelledWithItsFile() {
            String user = userText(service.buildPrompt(QUESTION, TWO_MATCHES));

            assertThat(user)
                    .contains("[02-jdbc-adapter.txt]\nAdd a receiver channel and select JDBC.")
                    .contains("[10-partner-directory.txt]\nPartner Directory stores partner-specific values.");
        }

        @Test
        void chunksAreSeparatedAndKeepRetrievalOrder() {
            String user = userText(service.buildPrompt(QUESTION, TWO_MATCHES));

            assertThat(user).containsSubsequence(
                    "[02-jdbc-adapter.txt]", "\n---\n", "[10-partner-directory.txt]");
        }

        @Test
        void questionComesLastAfterTheContext() {
            String user = userText(service.buildPrompt(QUESTION, TWO_MATCHES));

            assertThat(user).startsWith("Context:\n");
            assertThat(user).endsWith("Question: " + QUESTION);
        }

        @Test
        void scoresNeverReachTheModel() {
            String user = userText(service.buildPrompt(QUESTION, TWO_MATCHES));

            assertThat(user).doesNotContain("0.86").doesNotContain("0.85");
        }

        @Test
        void emptyRetrievalSaysSoInsteadOfSendingAnEmptyContext() {
            String user = userText(service.buildPrompt("What is the capital of France?", List.of()));

            assertThat(user).contains("(no relevant documents found)");
            assertThat(user).doesNotContain("---");
        }

        private static String userText(List<ChatMessage> messages) {
            return ((UserMessage) messages.get(1)).singleText();
        }
    }

    @Nested
    class Answer {

        @Test
        void returnsTheModelAnswerSourcesAndTokenCounts() {
            var chatModel = new FakeChatModel(response("Use the JDBC adapter.", new TokenUsage(349, 94)));
            var service = new RagService(retrievalReturning(TWO_MATCHES), chatModel);

            RagService.RagAnswer answer = service.answer(QUESTION);

            assertThat(answer.answer()).isEqualTo("Use the JDBC adapter.");
            assertThat(answer.retrievedFrom())
                    .containsExactly("02-jdbc-adapter.txt", "10-partner-directory.txt");
            assertThat(answer.inputTokens()).isEqualTo(349);
            assertThat(answer.outputTokens()).isEqualTo(94);
            assertThat(answer.millis()).isNotNegative();
        }

        @Test
        void sendsThePromptBuiltFromTheRetrievedChunks() {
            var chatModel = new FakeChatModel(response("ok", null));
            var service = new RagService(retrievalReturning(TWO_MATCHES), chatModel);

            service.answer(QUESTION);

            assertThat(chatModel.requests).hasSize(1);
            assertThat(chatModel.requests.getFirst().messages())
                    .isEqualTo(service.buildPrompt(QUESTION, TWO_MATCHES));
        }

        @Test
        void missingTokenUsageBecomesNullNotAnError() {
            var service = new RagService(retrievalReturning(List.of()),
                    new FakeChatModel(response("I don't know.", null)));

            RagService.RagAnswer answer = service.answer("What is the capital of France?");

            assertThat(answer.retrievedFrom()).isEmpty();
            assertThat(answer.inputTokens()).isNull();
            assertThat(answer.outputTokens()).isNull();
        }
    }
}
