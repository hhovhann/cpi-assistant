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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    /** A bracket holding only digits, commas and spaces: [1], [1, 2]. Not [SAP_ApplicationID]. */
    private static final Pattern CITATION = Pattern.compile("\\[([\\d,\\s]+)]");

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

        String text = response.aiMessage().text();
        TokenUsage usage = response.tokenUsage();
        return new RagAnswer(
            text,
            extractSources(text, matches),
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
                Cite the number of each context chunk you use, like [1] or [1, 2].
                """
        );
        String context = matches.isEmpty() ? "(no relevant documents found)"
            : IntStream.range(0, matches.size())
            .mapToObj(i -> "[" + (i + 1) + "] " + matches.get(i).embedded().metadata().getString("file_name")
                + "\n" + matches.get(i).embedded().text())
            .collect(Collectors.joining("\n---\n"));

        UserMessage userMessage = UserMessage.from("Context:\n" + context + "\n\nQuestion: " + question);

        return List.of(systemMessage, userMessage);
    }

    /**
     * Finds the chunks the answer actually cites, in order of first citation.
     * <p>
     * The prompt numbers the chunks [1], [2], [3] in retrieval order, so [n]
     * maps to matches.get(n - 1). Accepts [1], [1][2], [1, 2] and [1 2]. Numbers
     * with no chunk behind them are dropped: the model can invent a [7], and
     * a source that doesn't exist is worse than none.
     */
    List<Source> extractSources(String answer, List<EmbeddingMatch<TextSegment>> matches) {
        Set<Integer> cited = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            for (String digits : matcher.group(1).split("[,\\s]+")) {
                if (!digits.isEmpty() && digits.length() <= 3) {
                    cited.add(Integer.parseInt(digits));
                }
            }
        }
        return cited.stream()
            .filter(n -> n >= 1 && n <= matches.size())
            .map(n -> Source.of(n, matches.get(n - 1)))
            .toList();
    }

    /**
     * A chunk the answer cited: its number in the prompt, the file it came
     * from, its retrieval score, and the start of its text so a reader can
     * check the claim without opening the file.
     */
    public record Source(int number, String file, double score, String excerpt) {

        private static final int EXCERPT_LENGTH = 100;

        static Source of(int number, EmbeddingMatch<TextSegment> match) {
            String flat = match.embedded().text().replaceAll("\\s+", " ").trim();
            return new Source(number,
                    match.embedded().metadata().getString("file_name"),
                    match.score(),
                    flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "...");
        }
    }

    /**
     * What /ask returns. sources is what the answer cited; retrievedFrom is
     * everything retrieval handed over. The difference is the noise. Token
     * counts are the cost side of RAG: compare inputTokens here against the
     * ~20K it would take to stuff all docs in.
     */
    public record RagAnswer(String answer,
                            List<Source> sources,
                            List<String> retrievedFrom,
                            Integer inputTokens,
                            Integer outputTokens,
                            long millis) {
    }
}
