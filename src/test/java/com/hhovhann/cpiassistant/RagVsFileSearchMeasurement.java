package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * RAG against "let the model search the files", on the same questions, the
 * same model and the same knowledge — the ~1,660 official SAP pages.
 * <ul>
 *   <li><b>RAG</b>: {@link AssistService} — vector search over saved pages, a
 *       download on a miss, one answer.</li>
 *   <li><b>File search</b>: an agent with two tools and no index, the way a
 *       coding assistant works — searchFiles (grep over all pages as local
 *       Markdown) and readFile (a whole page).</li>
 * </ul>
 * Needs LM Studio, pgvector and the internet (the pages are downloaded once to
 * build/measure/sap-docs). Run with {@code ./gradlew measure}; the report goes
 * to build/measure/rag-vs-file-search.md.
 */
@Tag("measure")
@SpringBootTest(properties = {
        "cpi.store.type=pgvector",
        "cpi.knowledge.seed-on-startup=true",
        "cpi.chat.log-requests=false",
        "cpi.chat.log-responses=false"})
class RagVsFileSearchMeasurement {

    private static final Path PAGES = Path.of("build/measure/sap-docs");
    private static final Path REPORT = Path.of("build/measure/rag-vs-file-search.md");
    private static final int READ_LIMIT = 12_000;
    private static final int SEARCH_HITS = 20;
    private static final int MAX_ROUND_TRIPS = 10;

    /** A question and the facts a right answer contains: every group needs one of its words. */
    record Question(String text, List<List<String>> facts) {
    }

    private static final List<Question> QUESTIONS = List.of(
            new Question("How do I configure a JDBC adapter?", List.of(List.of("driver"), List.of("data source"))),
            new Question("How do I handle errors in an iFlow?", List.of(List.of("exception"), List.of("error"))),
            new Question("How do I configure the AS4 receiver adapter?", List.of(List.of("ebms3", "ebms 3"), List.of("msh", "message service handler"))),
            new Question("How do I configure the Kafka receiver adapter?", List.of(List.of("topic"), List.of("kafka"))),
            new Question("How do I set up an SFTP receiver with known hosts?", List.of(List.of("known host"), List.of("ssh", "host key", "public key"))),
            new Question("How do I connect to a database from an iFlow?", List.of(List.of("jdbc"))),
            new Question("What is the capital of France?", List.of(List.of("don't know", "not about", "not related", "outside"))));

    interface FileSearchAgent {
        @SystemMessage("""
                You answer SAP Cloud Integration (CPI) questions from the SAP documentation files.
                Use searchFiles to find pages — it takes keywords or a regular expression and returns \\
                matching lines, each with its page id — then readFile to read a page.
                Answer only from what you read, and cite page titles in square brackets.
                If the files do not cover it, or the question is not about CPI, say "I don't know".""")
        Result<String> answer(@UserMessage String question);
    }

    /** grep and cat over the downloaded pages. */
    static final class FileTools {
        private final Map<String, String> pages;
        private final Map<String, String> titles;

        FileTools(Map<String, String> pages, Map<String, String> titles) {
            this.pages = pages;
            this.titles = titles;
        }

        /**
         * Keywords: pages containing every word, ranked by how often they occur
         * — what chaining greps gives a person. A pattern with regex characters
         * is used as a regular expression instead. (The first version matched
         * the whole input literally: "JDBC adapter configuration" hit 0 pages
         * while 23 contain all three words, and the agent gave up.)
         */
        @Tool("""
                Searches all SAP documentation files, case-insensitive. Keywords: returns pages \
                containing all of them, best first. Up to 20 results as 'page id | page title | \
                a matching line'. A regular expression also works.""")
        public String searchFiles(@P("Keywords, e.g. 'JDBC receiver', or a regular expression") String pattern) {
            boolean regexMode = pattern.matches(".*[\\\\^$.|?*+()\\[\\]{}].*");
            List<String> words = List.of(pattern.toLowerCase(Locale.ROOT).split("\\s+"));
            Pattern regex = null;
            if (regexMode) {
                try {
                    regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    regexMode = false;
                }
            }
            record Hit(String id, int count, String line) {
            }
            List<Hit> hits = new ArrayList<>();
            for (Map.Entry<String, String> page : pages.entrySet()) {
                String text = page.getValue().toLowerCase(Locale.ROOT);
                int count = 0;
                String best = null;
                if (regexMode) {
                    Matcher m = regex.matcher(page.getValue());
                    while (m.find()) {
                        count++;
                    }
                } else if (words.stream().allMatch(text::contains)) {
                    for (String w : words) {
                        count += text.split(Pattern.quote(w), -1).length - 1;
                    }
                }
                if (count == 0) {
                    continue;
                }
                for (String line : page.getValue().split("\n")) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (regexMode ? regex.matcher(line).find() : words.stream().anyMatch(lower::contains)) {
                        best = line.strip();
                        break;
                    }
                }
                hits.add(new Hit(page.getKey(), count, best == null ? "" : best));
            }
            hits.sort((a, b) -> Integer.compare(b.count(), a.count()));
            if (hits.isEmpty()) {
                return "No matches for " + pattern;
            }
            return String.join("\n", hits.stream().limit(SEARCH_HITS)
                    .map(h -> h.id() + " | " + titles.get(h.id()) + " | "
                            + (h.line().length() > 160 ? h.line().substring(0, 160) + "…" : h.line()))
                    .toList());
        }

        @Tool("Returns the whole text of one SAP documentation page.")
        public String readFile(@P("The page id, as searchFiles returned it") String pageId) {
            String text = pages.get(pageId.strip());
            if (text == null) {
                return "No page with id " + pageId;
            }
            return text.length() > READ_LIMIT ? text.substring(0, READ_LIMIT) + "\n[truncated]" : text;
        }
    }

    /**
     * @param correct  the facts are in the answer, and it is not a decline
     *                 (except where declining is the right answer)
     * @param grounded the answer cites a page this approach actually gave the
     *                 model or had it read — otherwise facts may be from memory
     */
    record Run(String approach, Question question, String answer, int inputTokens, int outputTokens,
               int modelCalls, long millis, boolean correct, boolean grounded, String note) {
    }

    private final Map<String, String> titles = new LinkedHashMap<>();

    @Autowired
    AssistService assistService;
    @Autowired
    SapHelpCatalog catalog;
    @Autowired
    SapHelpClient sapHelpClient;
    @Autowired
    ChatModel chatModel;

    @Test
    void compare() throws Exception {
        catalog.pages().forEach(page -> titles.put(page.id(), page.title()));
        Map<String, String> pages = downloadAll();
        var fileSearch = AiServices.builder(FileSearchAgent.class)
                .chatModel(chatModel)
                .tools(new FileTools(pages, titles))
                .maxToolCallingRoundTrips(MAX_ROUND_TRIPS)
                .build();

        List<Run> runs = new ArrayList<>();
        for (Question question : QUESTIONS) {
            runs.add(rag(question));
            runs.add(fileSearch(fileSearch, question));
            System.out.println(runs.get(runs.size() - 2));
            System.out.println(runs.getLast());
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(runs, pages.size()));
        System.out.println("\n" + Files.readString(REPORT));
    }

    private Run rag(Question question) {
        long start = System.currentTimeMillis();
        var answer = assistService.assist(question.text());
        int calls = 1 + answer.toolCalls().size() + (int) answer.path().stream().filter(s -> s.contains("asking")).count();
        String note = String.join(" → ", answer.path().stream()
                .map(step -> step.replaceAll(" \\(.*?\\)", "").replaceAll(", best [0-9.]+", "")).toList());
        boolean grounded = answer.sources().stream().anyMatch(AssistService.Source::cited) && answer.unverifiedCitations().isEmpty();
        return new Run("RAG", question, answer.answer(), answer.inputTokens(), answer.outputTokens(), calls,
                System.currentTimeMillis() - start, correct(question, answer.answer()), grounded, note);
    }

    private Run fileSearch(FileSearchAgent agent, Question question) {
        long start = System.currentTimeMillis();
        try {
            Result<String> result = agent.answer(question.text());
            var usage = result.tokenUsage();
            String note = String.join(" → ", result.toolExecutions().stream()
                    .map(e -> e.request().name() + " " + e.request().arguments().replaceAll("\\s+", " ")).toList());
            // Grounded: it cites the title of a page it actually read.
            java.util.Set<String> read = new java.util.HashSet<>();
            result.toolExecutions().stream().filter(x -> x.request().name().equals("readFile"))
                    .forEach(x -> {
                        Matcher id = Pattern.compile("\"pageId\"\\s*:\\s*\"([^\"]+)\"").matcher(x.request().arguments());
                        if (id.find() && titles.containsKey(id.group(1))) {
                            read.add(titles.get(id.group(1)));
                        }
                    });
            boolean grounded = AssistService.citedTitles(result.content()).stream().anyMatch(read::contains);
            return new Run("File search", question, result.content(),
                    usage == null ? 0 : usage.inputTokenCount(), usage == null ? 0 : usage.outputTokenCount(),
                    result.toolExecutions().size() + 1, System.currentTimeMillis() - start,
                    correct(question, result.content()), grounded, note);
        } catch (RuntimeException e) {
            return new Run("File search", question, "(failed: " + e.getMessage() + ")", 0, 0, MAX_ROUND_TRIPS,
                    System.currentTimeMillis() - start, false, false, "stopped");
        }
    }

    private static boolean correct(Question question, String answer) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT).replace('’', '\'');
        boolean declines = text.strip().startsWith("i don't know") || text.contains("not covered");
        boolean declineIsRight = question.facts().stream().anyMatch(group -> group.contains("don't know"));
        return question.facts().stream().allMatch(group -> group.stream().anyMatch(text::contains))
                && (declineIsRight || !declines);
    }

    /** Every catalog page as cleaned Markdown, downloaded once into build/measure/sap-docs. */
    private Map<String, String> downloadAll() throws Exception {
        Files.createDirectories(PAGES);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            for (SapHelpCatalog.Page page : catalog.pages()) {
                Path file = PAGES.resolve(page.id() + ".md");
                if (!Files.exists(file)) {
                    pool.submit(() -> sapHelpClient.fetch(page.path()).ifPresent(text -> write(file, text)));
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
        }
        Map<String, String> pages = new LinkedHashMap<>();
        for (SapHelpCatalog.Page page : catalog.pages()) {
            Path file = PAGES.resolve(page.id() + ".md");
            if (Files.exists(file)) {
                pages.put(page.id(), Files.readString(file));
            }
        }
        return pages;
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String report(List<Run> runs, int pageCount) {
        StringBuilder md = new StringBuilder("# RAG vs file search\n\n")
                .append("Same model (the configured chat model), same %d SAP pages. One run per question.\n\n".formatted(pageCount))
                .append("Right: the key facts are there and it is not a decline. Grounded: it cites a page it was given or read.\n\n")
                .append("| Question | Approach | Tokens in | Tokens out | Model calls | Time | Right | Grounded | Path / tool calls |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Run run : runs) {
            md.append("| %s | %s | %,d | %,d | %d | %.1f s | %s | %s | %s |\n".formatted(
                    run.question().text(), run.approach(), run.inputTokens(), run.outputTokens(), run.modelCalls(),
                    run.millis() / 1000.0, run.correct() ? "✅" : "❌", run.grounded() ? "✅" : "—", run.note().replace("|", "/")));
        }
        md.append("\n## Totals\n\n| Approach | Tokens in | Tokens out | Model calls | Time | Right | Grounded |\n|---|---|---|---|---|---|---|\n");
        for (String approach : List.of("RAG", "File search")) {
            List<Run> mine = runs.stream().filter(r -> r.approach().equals(approach)).toList();
            md.append("| %s | %,d | %,d | %d | %.0f s | %d of %d | %d of %d |\n".formatted(approach,
                    mine.stream().mapToInt(Run::inputTokens).sum(), mine.stream().mapToInt(Run::outputTokens).sum(),
                    mine.stream().mapToInt(Run::modelCalls).sum(), mine.stream().mapToLong(Run::millis).sum() / 1000.0,
                    mine.stream().filter(Run::correct).count(), mine.size(),
                    mine.stream().filter(Run::grounded).count(), mine.size()));
        }
        md.append("\n## Answers\n");
        for (Run run : runs) {
            md.append("\n### %s — %s\n\n%s\n".formatted(run.approach(), run.question().text(),
                    run.answer() == null ? "" : run.answer().strip()));
        }
        return md.toString();
    }
}
