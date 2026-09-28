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
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one path every question takes.
 * <ol>
 *   <li>{@link KnowledgeService} finds documentation: the two best SAP pages
 *       for the question (downloaded and saved the first time) and the best
 *       passages in the store. No model call.</li>
 *   <li>{@link CpiAgent} answers with those passages in hand, and calls tools
 *       when it needs more: a whole page, the tenant, a skill, an MCP tool.</li>
 *   <li>Every page the answer cites is checked against what the model was
 *       actually given — the passages and every tool result. A bracketed name
 *       counts as unverified only if it appears nowhere in that text: the model
 *       also brackets pages a passage merely mentions ("see Configure JDBC
 *       Drivers"), which is not an invention.</li>
 * </ol>
 * The answer comes back with its path — what was searched, downloaded and
 * called.
 */
@Service
public class AssistService {

    static final String STOPPED = "I could not finish: this question needed more tool calls than allowed. "
            + "Ask more precisely — for example with the exact iFlow name.";
    static final String EMPTY = "The model returned no answer. Please ask again.";

    /**
     * [Page Title] — a citation. Not one: a bracket right after a word or a
     * slash, which is code (payload/LogEntry[severity = 'Error']), or one
     * holding "=" or quotes. Numbers, ids, URLs and tool names are filtered in
     * citedTitles.
     */
    private static final Pattern CITATION = Pattern.compile("(?<![\\w/])\\[([^\\[\\]\\n=\"'`]{3,150})]");
    /** Order_Sync, Payment_Status_Poll: words joined by underscores — how iFlows are named. */
    private static final Pattern IFLOW_NAME = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\\b");
    /** A page title at the start of a line in a tool result: how searchDocs and readPage label text. */
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
     * @param sources             every page the model was given — up front or by a tool
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
        KnowledgeService.Found found = knowledge.find(question);
        List<String> path = new ArrayList<>(found.steps());
        String note = iflowNote(question);
        if (!note.isEmpty()) {
            path.add("Note: the question names an iFlow — the model is told to check the tenant");
        }

        Result<String> result;
        boolean stopped = false;
        try {
            result = agent.answer(KnowledgeService.format(found.passages()), note, question);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("maxToolCallingRoundTrips")) {
                throw e;
            }
            // The safety limit stopped a model that kept calling tools. Say so
            // instead of failing the request with a 500.
            path.add("Stopped: more than %d rounds of tool calls".formatted(LangChain4jConfig.MAX_TOOL_ROUND_TRIPS));
            result = Result.<String>builder().content(STOPPED).toolExecutions(List.of()).build();
            stopped = true;
        }
        List<ToolCall> toolCalls = result.toolExecutions().stream()
                .map(e -> new ToolCall(e.request().name(), e.request().arguments(), e.result()))
                .toList();
        toolCalls.forEach(call -> path.add("Tool: " + call.tool() + " " + call.arguments()));
        if (!stopped) {
            path.add(toolCalls.isEmpty() ? "Answered from the passages, no tool" : "Answered");
        }

        // Qwen3 has returned an empty answer after only thinking; never pass on null.
        String answer = result.content() == null || result.content().isBlank() ? EMPTY : result.content();
        Set<String> cited = citedTitles(answer);
        Map<String, Source> given = sourcesGiven(found.passages(), toolCalls);
        List<Source> sources = given.values().stream()
                .map(s -> new Source(s.title(), s.url(), s.score(), s.excerpt(), cited.contains(s.title())))
                .toList();
        String seen = textSeen(found.passages(), toolCalls);
        List<String> unverified = cited.stream()
                .filter(title -> !given.containsKey(title) && !seen.contains(title.toLowerCase(Locale.ROOT)))
                .toList();
        TokenUsage usage = result.tokenUsage();
        return new AssistAnswer(answer, path, sources, unverified, toolCalls,
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                System.currentTimeMillis() - start);
    }

    /**
     * A hint, not a router: a name shaped like an iFlow's makes the model check
     * the tenant. Without it the model answered "Is Payment_Status_Poll
     * failing?" from documentation passages and never asked the tenant — the
     * same rule in the system prompt alone was not enough.
     */
    static String iflowNote(String question) {
        List<String> names = IFLOW_NAME.matcher(question).results().map(MatchResult::group).distinct().toList();
        return names.isEmpty() ? ""
                : "Note: " + String.join(", ", names) + " looks like an iFlow name. Check the tenant with the tools before answering.";
    }

    /** Pages the model saw, by title: the passages it was given, then any page a tool returned. */
    private Map<String, Source> sourcesGiven(List<EmbeddingMatch<TextSegment>> passages, List<ToolCall> toolCalls) {
        Map<String, Source> given = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : passages) {
            TextSegment segment = match.embedded();
            given.putIfAbsent(KnowledgeService.label(segment), new Source(KnowledgeService.label(segment),
                    segment.metadata().getString(KnowledgeService.URL), match.score(), excerpt(segment.text()), false));
        }
        for (ToolCall call : toolCalls) {
            if (call.result() == null) {
                continue;
            }
            Matcher m = RETURNED_TITLE.matcher(call.result());
            while (m.find()) {
                String title = m.group(1);
                catalog.pageByTitle(title).ifPresent(page ->
                        given.putIfAbsent(page.title(), new Source(page.title(), page.url(), null, null, false)));
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
                // A number, an id, a date, a URL, a tool name (listIflows) or a placeholder
                // from SAP's text ([<virtual host name>]) is not a page title.
                if (title.chars().anyMatch(Character::isLetter) && !title.matches("[0-9a-f]{16,}")
                        && !title.matches("[a-z]+[A-Z][A-Za-z]*") && !title.contains("://") && !title.contains("<")) {
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
