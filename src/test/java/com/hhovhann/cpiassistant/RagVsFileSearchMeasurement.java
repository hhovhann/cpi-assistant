package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG against "let the model search the files", on the same questions, the
 * same model and the same knowledge — the ~1,660 official SAP pages.
 * <ul>
 *   <li><b>RAG</b>: {@link AssistService} — vector search over saved pages, a
 *       download on a miss, one answer.</li>
 *   <li><b>File search</b>: an agent with two tools and no index, the way a
 *       coding assistant works — searchFiles (BM25 keyword search, or a regex,
 *       over all pages as local Markdown) and readFile (a page, in parts).</li>
 * </ul>
 * Fair by construction: its own table, reset to the seed pages every run; the
 * chat model is warmed up first and the two approaches alternate going first;
 * model calls and tokens are counted at the model, for both, even when a run
 * fails; one grounding rule for both.
 * <p>
 * Needs LM Studio, pgvector and the internet. {@code ./gradlew measure};
 * report in build/measure/rag-vs-file-search.md.
 */
@Tag("measure")
@SpringBootTest(properties = {
        "cpi.store.type=pgvector",
        "cpi.store.pgvector.table=cpi_chunks_measure",
        "cpi.knowledge.seed-on-startup=true",
        "cpi.chat.log-requests=false",
        "cpi.chat.log-responses=false"})
class RagVsFileSearchMeasurement {

    private static final Path PAGES = Path.of("build/measure/sap-docs");
    private static final Path REPORT = Path.of("build/measure/rag-vs-file-search.md");
    private static final int PART = 12_000;
    private static final int SEARCH_HITS = 20;
    private static final int MAX_ROUND_TRIPS = 10;

    /**
     * @param facts    every group needs one of its words in a right answer
     * @param offTopic the right answer is a decline
     */
    record Question(String text, List<List<String>> facts, boolean offTopic) {
    }

    private static final List<Question> QUESTIONS = List.of(
            new Question("How do I configure a JDBC adapter?", List.of(List.of("driver"), List.of("data source")), false),
            new Question("How do I handle errors in an iFlow?", List.of(List.of("exception", "error handling"), List.of("error")), false),
            new Question("How do I configure the AS4 receiver adapter?", List.of(List.of("ebms3", "ebms 3"), List.of("msh", "message service handler")), false),
            new Question("How do I configure the Kafka receiver adapter?", List.of(List.of("topic"), List.of("kafka")), false),
            new Question("How do I set up an SFTP receiver with known hosts?", List.of(List.of("known host"), List.of("ssh", "host key", "public key")), false),
            new Question("How do I connect to a database from an iFlow?", List.of(List.of("jdbc")), false),
            new Question("What is the capital of France?", List.of(), true));

    /** Counts every model call and its tokens, whoever makes it. */
    static final class CountingChatModel implements ChatModel {
        private final ChatModel delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inputTokens = new AtomicInteger();
        final AtomicInteger outputTokens = new AtomicInteger();

        CountingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        void reset() {
            calls.set(0);
            inputTokens.set(0);
            outputTokens.set(0);
        }

        private ChatResponse count(ChatResponse response) {
            calls.incrementAndGet();
            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                inputTokens.addAndGet(usage.inputTokenCount() == null ? 0 : usage.inputTokenCount());
                outputTokens.addAndGet(usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
            }
            return response;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return count(delegate.chat(request));
        }

        @Override
        public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
            return count(delegate.chat(request, options));
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return count(delegate.chat(request));
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return delegate.defaultRequestParameters();
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return delegate.supportedCapabilities();
        }

        @Override
        public ModelProvider provider() {
            return delegate.provider();
        }
    }

    /** The assistant gets the counting model too, so RAG's calls are counted the same way. */
    @TestConfiguration
    static class CountingConfig {
        @Bean
        @Primary
        CountingChatModel countingChatModel(@Qualifier("chatModel") ChatModel chatModel) {
            return new CountingChatModel(chatModel);
        }
    }

    interface FileSearchAgent {
        @SystemMessage("""
                You answer SAP Cloud Integration (CPI) questions from the SAP documentation files.
                Use searchFiles to find pages: give it a few keywords, and it returns the best \
                matching pages, each with its page id. Then read a page with readFile.
                Answer only from what you read, and cite page titles in square brackets.
                If the files do not cover it, or the question is not about CPI, say "I don't know".""")
        Result<String> answer(@UserMessage String question);
    }

    /** Search and read over the downloaded pages — BM25, the same ranking RAG uses for keywords. */
    static final class FileTools {
        private final Map<String, String> pages;
        private final Map<String, String> titles;
        private final KeywordSearch keywords;

        FileTools(Map<String, String> pages, Map<String, String> titles) {
            this.pages = pages;
            this.titles = titles;
            Map<String, String> texts = new LinkedHashMap<>();
            pages.forEach((id, text) -> texts.put(id, titles.get(id) + "\n" + text));
            this.keywords = new KeywordSearch(texts);
        }

        @Tool("""
                Searches all SAP documentation files. Give keywords, e.g. 'JDBC receiver adapter': \
                returns up to 20 pages, best first (BM25), as 'page id | page title | a matching \
                line'. A regular expression between slashes also works, e.g. /ebMS3 (Push|Pull)/.""")
        public String searchFiles(@P("Keywords, or a regular expression between slashes") String query) {
            String q = query == null ? "" : query.strip();
            if (q.length() > 2 && q.startsWith("/") && q.endsWith("/")) {
                return regexSearch(q.substring(1, q.length() - 1));
            }
            List<String> terms = KeywordSearch.terms(q);
            if (terms.isEmpty()) {
                return "Give one or more keywords.";
            }
            var hits = keywords.rank(q, SEARCH_HITS);
            if (hits.isEmpty()) {
                return "No matches for " + q;
            }
            return String.join("\n", hits.stream()
                    .map(h -> h.id() + " | " + titles.get(h.id()) + " | " + line(pages.get(h.id()), terms))
                    .toList());
        }

        private String regexSearch(String expression) {
            Pattern regex;
            try {
                regex = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                return "Not a valid regular expression: " + e.getDescription();
            }
            List<String> hits = new ArrayList<>();
            for (Map.Entry<String, String> page : pages.entrySet()) {
                Matcher m = regex.matcher(page.getValue());
                if (m.find()) {
                    hits.add(page.getKey() + " | " + titles.get(page.getKey()) + " | " + line(page.getValue(), List.of(m.group())));
                    if (hits.size() == SEARCH_HITS) {
                        break;
                    }
                }
            }
            return hits.isEmpty() ? "No matches for /" + expression + "/" : String.join("\n", hits);
        }

        /** The first line holding one of the words, most words first. */
        private static String line(String text, List<String> words) {
            String best = "";
            long bestCount = 0;
            for (String line : text.split("\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                long count = words.stream().filter(w -> lower.contains(w.toLowerCase(Locale.ROOT))).count();
                if (count > bestCount) {
                    bestCount = count;
                    best = line.strip();
                }
            }
            return best.length() > 160 ? best.substring(0, 160) + "…" : best;
        }

        @Tool("""
                Returns one SAP documentation page. Long pages come in parts of 12,000 characters: \
                the answer says 'part 1 of 3'; ask for the next part to read on.""")
        public String readFile(@P("The page id, as searchFiles returned it") String pageId,
                               @P(value = "Which part, starting at 1. Default 1.", required = false) Integer part) {
            String text = pages.get(pageId == null ? "" : pageId.strip());
            if (text == null) {
                return "No page with id " + pageId;
            }
            int parts = Math.max(1, (text.length() + PART - 1) / PART);
            int p = part == null || part < 1 ? 1 : Math.min(part, parts);
            String slice = text.substring((p - 1) * PART, Math.min(text.length(), p * PART));
            return parts == 1 ? slice : "(part %d of %d)\n%s".formatted(p, parts, slice);
        }
    }

    /**
     * @param correct  the facts are in the answer and it is not a decline — or,
     *                 off-topic, it is a decline
     * @param grounded it cites at least one page it was given or read, and no
     *                 page it was not (the same rule for both approaches)
     * @param failed   the run threw; tokens and calls up to that point still count
     */
    record Run(String approach, Question question, String answer, int inputTokens, int outputTokens,
               int modelCalls, long millis, boolean correct, boolean grounded, boolean failed, String note) {
    }

    private final Map<String, String> titles = new LinkedHashMap<>();

    @Autowired
    AssistService assistService;
    @Autowired
    SapHelpCatalog catalog;
    @Autowired
    SapHelpClient sapHelpClient;
    @Autowired
    CountingChatModel model;
    @Autowired
    EmbeddingStore<TextSegment> store;
    @Autowired
    SeedProperties seed;

    @Test
    void compare() throws Exception {
        catalog.pages().forEach(page -> titles.put(page.id(), page.title()));
        Map<String, String> pages = downloadAll();
        int keptPages = resetStoreToSeedPages();
        var fileSearch = AiServices.builder(FileSearchAgent.class)
                .chatModel(model)
                .tools(new FileTools(pages, titles))
                .maxToolCallingRoundTrips(MAX_ROUND_TRIPS)
                .build();

        model.chat("Say OK.");   // load the model before anything is timed

        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < QUESTIONS.size(); i++) {
            Question question = QUESTIONS.get(i);
            // Alternate who goes first, so neither side always gets the warm cache.
            if (i % 2 == 0) {
                runs.add(rag(question));
                runs.add(fileSearch(fileSearch, question));
            } else {
                runs.add(fileSearch(fileSearch, question));
                runs.add(rag(question));
            }
            System.out.println(runs.get(runs.size() - 2));
            System.out.println(runs.getLast());
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(runs, pages.size(), keptPages));
        System.out.println("\n" + Files.readString(REPORT));
    }

    /** The measurement's own table, back to the seed pages: every run starts like a first one. */
    private int resetStoreToSeedPages() {
        List<String> seedUrls = seed.seedPages().stream()
                .map(id -> catalog.page(id).orElseThrow(() -> new IllegalStateException("Seed page not in catalog: " + id)).url())
                .toList();
        store.removeAll(metadataKey(KnowledgeService.URL).isNotIn(seedUrls));
        return seedUrls.size();
    }

    private Run rag(Question question) {
        model.reset();
        long start = System.currentTimeMillis();
        try {
            var answer = assistService.assist(question.text());
            String note = String.join(" → ", answer.path().stream()
                    .map(step -> step.replaceAll(" \\(.*?\\)", "").replaceAll(", best [0-9.]+", "").replaceAll("\\s+", " "))
                    .toList());
            // The app checks citations against the full text the model read.
            boolean grounded = answer.sources().stream().anyMatch(AssistService.Source::cited)
                    && answer.unverifiedCitations().isEmpty();
            return new Run("RAG", question, answer.answer(), model.inputTokens.get(), model.outputTokens.get(),
                    model.calls.get(), System.currentTimeMillis() - start, correct(question, answer.answer()),
                    grounded, false, note);
        } catch (RuntimeException e) {
            return failed("RAG", question, start, e);
        }
    }

    private Run fileSearch(FileSearchAgent agent, Question question) {
        model.reset();
        long start = System.currentTimeMillis();
        try {
            Result<String> result = agent.answer(question.text());
            Set<String> read = new HashSet<>();
            StringBuilder seen = new StringBuilder();
            for (ToolExecution execution : result.toolExecutions()) {
                seen.append(execution.result()).append('\n');
                if (execution.request().name().equals("readFile") && !String.valueOf(execution.result()).startsWith("No page")) {
                    Matcher id = Pattern.compile("\"pageId\"\\s*:\\s*\"([^\"]+)\"").matcher(execution.request().arguments());
                    if (id.find() && titles.containsKey(id.group(1).strip())) {
                        read.add(titles.get(id.group(1).strip()));
                    }
                }
            }
            String note = String.join(" → ", result.toolExecutions().stream()
                    .map(e -> e.request().name() + " " + e.request().arguments().replaceAll("\\s+", " ")).toList());
            return new Run("File search", question, result.content(), model.inputTokens.get(), model.outputTokens.get(),
                    model.calls.get(), System.currentTimeMillis() - start, correct(question, result.content()),
                    grounded(result.content(), read, seen.toString()), false, note);
        } catch (RuntimeException e) {
            return failed("File search", question, start, e);
        }
    }

    private Run failed(String approach, Question question, long start, RuntimeException e) {
        return new Run(approach, question, "(failed: " + e.getMessage() + ")", model.inputTokens.get(),
                model.outputTokens.get(), model.calls.get(), System.currentTimeMillis() - start, false, false, true,
                "failed: " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
    }

    private static boolean correct(Question question, String answer) {
        String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT).replace('’', '\'');
        boolean declines = text.strip().startsWith("i don't know") || text.contains("not covered") || text.contains("not about");
        if (question.offTopic()) {
            return declines;
        }
        return !declines && question.facts().stream().allMatch(group -> group.stream().anyMatch(text::contains));
    }

    /** At least one cited page it had; no cited page it neither had nor saw mentioned. */
    private static boolean grounded(String answer, Set<String> given, String seen) {
        Set<String> cited = AssistService.citedTitles(answer);
        String seenLower = seen.toLowerCase(Locale.ROOT);
        boolean citesGiven = cited.stream().anyMatch(given::contains);
        boolean invents = cited.stream().anyMatch(t -> !given.contains(t) && !seenLower.contains(t.toLowerCase(Locale.ROOT)));
        return citesGiven && !invents;
    }

    /** Every catalog page as cleaned Markdown, downloaded once; fails loudly if any is missing. */
    private Map<String, String> downloadAll() throws Exception {
        Files.createDirectories(PAGES);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> downloads = new ArrayList<>();
        for (SapHelpCatalog.Page page : catalog.pages()) {
            Path file = PAGES.resolve(page.id() + ".md");
            if (!Files.exists(file)) {
                downloads.add(pool.submit(() -> sapHelpClient.fetch(page.path()).ifPresent(text -> write(file, text))));
            }
        }
        pool.shutdown();
        if (!pool.awaitTermination(15, TimeUnit.MINUTES)) {
            throw new IllegalStateException("Downloading the SAP pages took longer than 15 minutes");
        }
        List<String> errors = new ArrayList<>();
        for (Future<?> download : downloads) {
            try {
                download.get();
            } catch (java.util.concurrent.ExecutionException e) {
                errors.add(String.valueOf(e.getCause().getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.size() + " page downloads failed, e.g. " + errors.getFirst());
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

    /** Write to a temporary file, then move: an interrupted run never leaves half a page. */
    private static void write(Path file, String text) {
        try {
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String report(List<Run> runs, int pageCount, int seedPages) {
        StringBuilder md = new StringBuilder("# RAG vs file search\n\n")
                .append(String.format(Locale.ROOT, "Same model, same %d SAP pages. The RAG store starts from the %d seed pages "
                        + "(its own table, reset every run). One run per question; the two approaches alternate going first.%n%n", pageCount, seedPages))
                .append("Right: the key facts are there and it is not a decline (off-topic: it declines). "
                        + "Grounded: it cites a page it was given or read, and none it wasn't. "
                        + "Model calls and tokens are counted at the model.\n\n")
                .append("| Question | Approach | Tokens in | Tokens out | Model calls | Time | Right | Grounded | Path / tool calls |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Run run : runs) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %,d | %,d | %d | %.1f s | %s | %s | %s |%n",
                    run.question().text(), run.approach(), run.inputTokens(), run.outputTokens(), run.modelCalls(),
                    run.millis() / 1000.0, run.correct() ? "✅" : "❌",
                    run.question().offTopic() ? "n/a" : run.grounded() ? "✅" : "—", run.note().replace("|", "/")));
        }
        md.append("\n## Totals\n\n| Approach | Tokens in | Tokens out | Model calls | Time | Right | Grounded (docs questions) | Failed |\n"
                + "|---|---|---|---|---|---|---|---|\n");
        for (String approach : List.of("RAG", "File search")) {
            List<Run> mine = runs.stream().filter(r -> r.approach().equals(approach)).toList();
            List<Run> docs = mine.stream().filter(r -> !r.question().offTopic()).toList();
            md.append(String.format(Locale.ROOT, "| %s | %,d | %,d | %d | %.0f s | %d of %d | %d of %d | %d |%n", approach,
                    mine.stream().mapToInt(Run::inputTokens).sum(), mine.stream().mapToInt(Run::outputTokens).sum(),
                    mine.stream().mapToInt(Run::modelCalls).sum(), mine.stream().mapToLong(Run::millis).sum() / 1000.0,
                    mine.stream().filter(Run::correct).count(), mine.size(),
                    docs.stream().filter(Run::grounded).count(), docs.size(),
                    mine.stream().filter(Run::failed).count()));
        }
        md.append("\n## Answers\n");
        for (Run run : runs) {
            md.append("\n### %s — %s\n\n%s\n".formatted(run.approach(), run.question().text(),
                    run.answer() == null ? "" : run.answer().strip()));
        }
        return md.toString();
    }
}
