package com.hhovhann.cpiassistant;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The full RAG loop: retrieve, augment, generate.
 * <p>
 * The LLM never sees the vector store. All it gets is text: the chunks
 * retrieval picked, pasted into the prompt next to the question. Everything
 * that makes RAG work or fail happens in {@link #buildPrompt}.
 */
@Service
public class RagService {

    /**
     * How many chunks go into the prompt. 3 × ~500 chars ≈ 400 tokens of context.
     */
    private static final int MAX_CHUNKS = 3;

    private final RetrievalService retrievalService;
    private final ChatModel chatModel;

    public RagService(RetrievalService retrievalService, ChatModel chatModel) {
        this.retrievalService = retrievalService;
        this.chatModel = chatModel;
    }

    public RagAnswer answer(String question) {
        List<EmbeddingMatch<TextSegment>> matches = retrievalService.search(question, MAX_CHUNKS);

        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.chat(buildPrompt(question, matches));
        long millis = System.currentTimeMillis() - start;

        TokenUsage usage = response.tokenUsage();
        return new RagAnswer(
            response.aiMessage().text(),
            matches.stream().map(m -> m.embedded().metadata().getString("file_name")).toList(),
            usage == null ? null : usage.inputTokenCount(),
            usage == null ? null : usage.outputTokenCount(),
            millis
        );
    }

    /**
     * Turns the question and the retrieved chunks into the messages the model sees.
     * <p>
     * The system message holds the rules — above all, answer only from the
     * context — because Llama 8B follows rules more reliably there. The user
     * message holds the data: each chunk labelled with its source file,
     * separated by "---", then the question.
     * <p>
     * Scores stay out of the prompt; they mean nothing to the model. An empty
     * retrieval is spelled out rather than sent as a blank context, so the
     * model has an explicit reason to answer "I don't know".
     */
    List<ChatMessage> buildPrompt(String question, List<EmbeddingMatch<TextSegment>> matches) {
        SystemMessage systemMessage = SystemMessage.from(
            """
                You are a SAP CPI assistant. Answer ONLY from the context.
                If the context doesn't contain the answer, say "I don't know".
                """
        );
        String context = matches.isEmpty() ? "(no relevant documents found)"
            : matches.stream()
            .map(m -> "[" + m.embedded().metadata().getString("file_name") + "]\n" + m.embedded().text())
            .collect(Collectors.joining("\n---\n"));

        UserMessage userMessage = UserMessage.from("Context:\n" + context + "\n\nQuestion: " + question);

        return List.of(systemMessage, userMessage);
    }

    /**
     * What /ask returns. Token counts are the cost side of RAG: compare
     * inputTokens here against the ~20K it would take to stuff all docs in.
     */
    public record RagAnswer(String answer,
                            List<String> retrievedFrom,
                            Integer inputTokens,
                            Integer outputTokens,
                            long millis) {
    }
}
