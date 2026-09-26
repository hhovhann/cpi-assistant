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
 *   <li>{@link KnowledgeService} finds documentation: the store, or SAP Help
 *       on a miss (downloaded and saved). No model call.</li>
 *   <li>{@link CpiAgent} answers with those passages in hand. A documentation
 *       question needs no tool; a question about the live tenant makes the
 *       model call the tenant tools. The model decides, not a router.</li>
 *   <li>If the answer is "I don't know" although passages were found, they
 *       were close but wrong (AS2 passages for an AS4 question): SAP Help is
 *       asked once, and the question answered again if a page was downloaded.</li>
 * </ol>
 * The answer comes back with its path — what was searched, downloaded and
 * called — and every page it cites is checked against what the model was
 * actually given. A bracketed name counts as unverified only if it appears
 * nowhere in that text: the model also brackets pages a passage merely
 * mentions ("see Configure JDBC Drivers"), which is not an invention.
 */
@Service
public class AssistService {

    /** [Page Title] — a citation. Numbers and message ids in brackets are not. */
    private static final Pattern CITATION = Pattern.compile("\\[([^\\[\\]\\n]{3,150})]");
    private static final Pattern RETURNED_TITLE = Pattern.compile("(?m)^\\[([^\\]\\n]+)]$");
    private static final int EXCERPT_LENGTH = 160;

    private final KnowledgeService knowledge;
    private final CpiAgent agent;
    private final SapHelpCatalog catalog;

    public AssistService(KnowledgeService knowledge, CpiAgent agent, SapHelpCatalog catalog) {
        this.knowledge = knowledge;
        this.agent = agent;
        this.catalog = catalog;
    }

    /**
     * @param path                what happened, in order
     * @param sources             every page the model was given — up front or by searchDocs
     * @param unverifiedCitations cited titles the model was never given
     */
    public record AssistAnswer(String answer,
                               List<String> path,
                               List<Source> sources,
                               List<String> unverifiedCitations,
                               List<ToolCall> toolCalls,
                               int inputTokens,
                               int outputTokens,
                               long millis) {
    }

    public record Source(String title, String url, Double score, String excerpt, boolean cited) {
    }

    public record ToolCall(String tool, String arguments, String result) {
    }

    public AssistAnswer assist(String question) {
        long start = System.currentTimeMillis();
        List<String> path = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        int[] tokens = new int[2];

        KnowledgeService.Found found = knowledge.find(question);
        path.addAll(found.steps());
        Result<String> result = ask(question, found, path, toolCalls, tokens);

        if (KnowledgeService.isIDontKnow(result.content()) && found.downloaded().isEmpty() && !found.passages().isEmpty()) {
            path.add("The passages did not answer it: asking SAP Help");
            KnowledgeService.Found more = knowledge.fetchFromSapHelp(question);
            path.addAll(more.steps());
            if (!more.downloaded().isEmpty()) {
                found = more;
                result = ask(question, found, path, toolCalls, tokens);
            }
        }

        String answer = result.content();
        Map<String, Source> given = sourcesGiven(found.passages(), toolCalls);
        Set<String> cited = citedTitles(answer);
        List<Source> sources = given.values().stream()
                .map(s -> new Source(s.title(), s.url(), s.score(), s.excerpt(), cited.contains(s.title())))
                .toList();
        String seen = textSeen(found.passages(), toolCalls);
        List<String> unverified = cited.stream()
                .filter(title -> !given.containsKey(title) && !seen.contains(title.toLowerCase(Locale.ROOT)))
                .toList();
        return new AssistAnswer(answer, path, sources, unverified, toolCalls, tokens[0], tokens[1],
                System.currentTimeMillis() - start);
    }

    private Result<String> ask(String question, KnowledgeService.Found found, List<String> path,
                               List<ToolCall> toolCalls, int[] tokens) {
        Result<String> result = agent.answer(KnowledgeService.format(found.passages()), question);
        result.toolExecutions().forEach(e -> {
            toolCalls.add(new ToolCall(e.request().name(), e.request().arguments(), e.result()));
            path.add("Tool: " + e.request().name() + " " + e.request().arguments());
        });
        TokenUsage usage = result.tokenUsage();
        if (usage != null) {
            tokens[0] += usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            tokens[1] += usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        }
        path.add(result.toolExecutions().isEmpty() ? "Answered from the passages, no tool" : "Answered");
        return result;
    }

    /** Pages the model saw, by title: the passages it was given, then any searchDocs returned. */
    private Map<String, Source> sourcesGiven(List<EmbeddingMatch<TextSegment>> passages, List<ToolCall> toolCalls) {
        Map<String, Source> given = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : passages) {
            TextSegment segment = match.embedded();
            given.putIfAbsent(KnowledgeService.label(segment), new Source(KnowledgeService.label(segment),
                    segment.metadata().getString(KnowledgeService.URL), match.score(), excerpt(segment.text()), false));
        }
        for (ToolCall call : toolCalls) {
            if (!call.tool().equals("searchDocs") || call.result() == null) {
                continue;
            }
            Matcher m = RETURNED_TITLE.matcher(call.result());
            while (m.find()) {
                String title = m.group(1);
                given.putIfAbsent(title, new Source(title,
                        catalog.pageByTitle(title).map(SapHelpCatalog.Page::url).orElse(null), null, null, false));
            }
        }
        return given;
    }

    /** Everything the model read — passages and tool results — lower-cased, to find mentioned names. */
    private static String textSeen(List<EmbeddingMatch<TextSegment>> passages, List<ToolCall> toolCalls) {
        StringBuilder seen = new StringBuilder();
        passages.forEach(match -> seen.append(match.embedded().text()).append('\n'));
        toolCalls.forEach(call -> seen.append(call.result()).append('\n'));
        return seen.toString().toLowerCase(Locale.ROOT);
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
                // A number, an id or a date is not a page title.
                if (title.chars().anyMatch(Character::isLetter) && !title.matches("[0-9a-f]{16,}")) {
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
