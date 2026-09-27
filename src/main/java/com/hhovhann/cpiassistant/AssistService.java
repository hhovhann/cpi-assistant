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

    static final String STOPPED = "I could not finish: this question needed more tool calls than allowed. "
            + "Ask more precisely — for example with the exact iFlow name.";

    /**
     * [Page Title] — a citation. Not one: a bracket right after a word or a
     * slash, which is code (payload/LogEntry[severity = 'Error']), or one
     * holding "=" or quotes. Numbers and ids are filtered in citedTitles.
     */
    private static final Pattern CITATION = Pattern.compile("(?<![\\w/])\\[([^\\[\\]\\n=\"'`]{3,150})]");
    private static final Pattern TOOL_NAME = Pattern.compile("\\b(listIflows|getProblemMessages|getErrorDetails|searchDocs)\\b");
    static final String CALL_DONT_DESCRIBE =
            "\nNote: call the tools you need. Do not describe a tool call in your answer — make it.\n";

    /** Order_Sync, Payment_Status_Poll: words joined by underscores — how iFlows are named. */
    private static final Pattern IFLOW_NAME = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\\b");
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
        Result<String> result = ask(question, found, "", path, toolCalls, tokens);

        if (describesToolInsteadOfCalling(result)) {
            path.add("The model described a tool call instead of making it: asking again");
            result = ask(question, found, CALL_DONT_DESCRIBE, path, toolCalls, tokens);
        }

        if (KnowledgeService.isIDontKnow(result.content()) && found.downloaded().isEmpty() && !found.passages().isEmpty()) {
            path.add("The passages did not answer it: asking SAP Help");
            KnowledgeService.Found more = knowledge.fetchFromSapHelp(question);
            path.addAll(more.steps());
            if (!more.downloaded().isEmpty()) {
                found = more;
                result = ask(question, found, "", path, toolCalls, tokens);
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

    private Result<String> ask(String question, KnowledgeService.Found found, String extraNote, List<String> path,
                               List<ToolCall> toolCalls, int[] tokens) {
        Result<String> result;
        try {
            result = agent.answer(KnowledgeService.format(found.passages()), notes(question, path) + extraNote, question);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("maxToolCallingRoundTrips")) {
                throw e;
            }
            // The safety limit stopped a model that kept calling tools. Say so
            // instead of failing the request with a 500.
            path.add("Stopped: more than %d rounds of tool calls".formatted(LangChain4jConfig.MAX_TOOL_ROUND_TRIPS));
            return Result.<String>builder().content(STOPPED).toolExecutions(List.of()).build();
        }
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

    /**
     * A small model sometimes writes "use getProblemMessages with …" as its
     * answer instead of calling the tool ("Why did Unknown_Flow fail?", every
     * run). An answer that names a tool while no tool ran gets one more try.
     */
    static boolean describesToolInsteadOfCalling(Result<String> result) {
        return result.toolExecutions().isEmpty() && result.content() != null
                && TOOL_NAME.matcher(result.content()).find();
    }

    /**
     * A hint, not a router: a name shaped like an iFlow's makes the model check
     * the tenant. Without it, once the store held a page about failed
     * connections, "Why did Unknown_Flow fail?" was answered from that page and
     * the tenant was never asked — even with the rule in the system prompt.
     */
    static String notes(String question, List<String> path) {
        Matcher m = IFLOW_NAME.matcher(question);
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group());
        }
        if (names.isEmpty()) {
            return "";
        }
        String list = String.join(", ", names);
        if (path != null && path.stream().noneMatch(step -> step.startsWith("Note:"))) {
            path.add("Note: " + list + " looks like an iFlow name — the model is told to check the tenant");
        }
        return "\nNote: " + list + " looks like an iFlow name. Check the tenant with the tools before answering.\n";
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
                // A number, an id, a date or a tool name (listIflows) is not a page title.
                if (title.chars().anyMatch(Character::isLetter) && !title.matches("[0-9a-f]{16,}")
                        && !title.matches("[a-z]+[A-Z][A-Za-z]*")) {
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
