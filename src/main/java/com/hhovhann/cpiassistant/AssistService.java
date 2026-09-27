package com.hhovhann.cpiassistant;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one path every question takes.
 * <ol>
 *   <li>{@link KnowledgeService} finds documentation: the best SAP page for the
 *       question (downloaded and saved the first time) and the best passages
 *       in the store. No model call.</li>
 *   <li>{@link CpiAgent} answers from those passages: one model call.</li>
 *   <li>Every page the answer cites is checked against what the model was
 *       actually given. A bracketed name counts as unverified only if it
 *       appears nowhere in that text: the model also brackets pages a passage
 *       merely mentions ("see Configure JDBC Drivers"), which is not an
 *       invention.</li>
 * </ol>
 * The answer comes back with its path — what was searched and downloaded.
 */
@Service
public class AssistService {

    static final String EMPTY = "The model returned no answer. Please ask again.";

    /**
     * [Page Title] — a citation. Not one: a bracket right after a word or a
     * slash, which is code (payload/LogEntry[severity = 'Error']), or one
     * holding "=" or quotes. Numbers, ids and URLs are filtered in citedTitles.
     */
    private static final Pattern CITATION = Pattern.compile("(?<![\\w/])\\[([^\\[\\]\\n=\"'`]{3,150})]");
    private static final int EXCERPT_LENGTH = 160;

    private final KnowledgeService knowledge;
    private final CpiAgent agent;

    public AssistService(KnowledgeService knowledge, CpiAgent agent) {
        this.knowledge = knowledge;
        this.agent = agent;
    }

    /**
     * @param path                what happened, in order
     * @param sources             every page the model was given
     * @param unverifiedCitations cited titles the model was never given
     */
    public record AssistAnswer(String answer,
                               List<String> path,
                               List<Source> sources,
                               List<String> unverifiedCitations,
                               int inputTokens,
                               int outputTokens,
                               long millis) {
    }

    public record Source(String title, String url, Double score, String excerpt, boolean cited) {
    }

    public AssistAnswer assist(String question) {
        long start = System.currentTimeMillis();
        KnowledgeService.Found found = knowledge.find(question);
        List<String> path = new ArrayList<>(found.steps());

        Result<String> result = agent.answer(KnowledgeService.format(found.passages()), question);
        path.add("Answered");

        // Qwen3 has returned an empty answer after only thinking; never pass on null.
        String answer = result.content() == null || result.content().isBlank() ? EMPTY : result.content();
        Set<String> cited = citedTitles(answer);
        Map<String, Source> given = new LinkedHashMap<>();
        StringBuilder seen = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : found.passages()) {
            TextSegment segment = match.embedded();
            String title = KnowledgeService.label(segment);
            given.putIfAbsent(title, new Source(title, segment.metadata().getString(KnowledgeService.URL),
                    match.score(), excerpt(segment.text()), cited.contains(title)));
            seen.append(segment.text().toLowerCase(Locale.ROOT)).append('\n');
        }
        List<String> unverified = cited.stream()
                .filter(title -> !given.containsKey(title) && !seen.toString().contains(title.toLowerCase(Locale.ROOT)))
                .toList();
        TokenUsage usage = result.tokenUsage();
        return new AssistAnswer(answer, path, List.copyOf(given.values()), unverified,
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                System.currentTimeMillis() - start);
    }

    static Set<String> citedTitles(String answer) {
        Set<String> titles = new LinkedHashSet<>();
        if (answer == null) {
            return titles;
        }
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            for (String part : m.group(1).split("\\]\\s*\\[")) {
                String title = part.strip();
                // A number, an id, a date or a URL is not a page title.
                if (title.chars().anyMatch(Character::isLetter) && !title.matches("[0-9a-f]{16,}") && !title.contains("://")) {
                    titles.add(title);
                }
            }
        }
        return titles;
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "…";
    }
}
