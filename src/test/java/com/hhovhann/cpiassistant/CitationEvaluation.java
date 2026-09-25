package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.RelevanceScore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * Citation evaluation: when an answer cites [2], does chunk 2 really say what
 * the sentence claims?
 * <p>
 * Each answer is split into claims — a sentence or list item and the citation
 * markers in it — giving (claim, cited chunk) pairs. Every pair is checked two
 * ways:
 * <ul>
 *   <li>similarity — the claim's embedding against the chunk's; cheap, no LLM</li>
 *   <li>an LLM judge — a separate prompt asking whether the passage supports
 *       the statement: SUPPORTED, PARTIAL or UNSUPPORTED</li>
 * </ul>
 * The judge is whatever chat model is active: Llama by default, which means it
 * grades its own answers; Claude under the `claude` profile. The report lists
 * every pair with an empty "Your verdict" column, because a judge is only as
 * useful as its agreement with someone who knows the domain.
 * <p>
 * Needs LM Studio; the all-docs setup needs a 32K context. Run with
 * {@code ./gradlew eval --tests '*CitationEvaluation'}. Report:
 * build/eval/citation-report.md.
 */
@Tag("eval")
@SpringBootTest(properties = {
        "cpi.ingestion.run-on-startup=false",
        "langchain4j.open-ai.chat-model.log-requests=false",
        "langchain4j.open-ai.chat-model.log-responses=false"})
class CitationEvaluation {

    private static final int K = 3;

    /** Shorter than this, once markers are stripped, a "claim" is a bare citation. */
    private static final int MIN_CLAIM_WORDS = 4;

    private static final String JUDGE_RULES = """
            You check citations. Given a PASSAGE and a STATEMENT, decide whether the passage supports the statement.
            SUPPORTED: everything the statement claims is stated in, or directly follows from, the passage.
            PARTIAL: some of what the statement claims is in the passage, some is not.
            UNSUPPORTED: the passage does not back the statement, or is about something else.
            Reply with exactly one word: SUPPORTED, PARTIAL or UNSUPPORTED.
            """;

    enum Verdict { SUPPORTED, PARTIAL, UNSUPPORTED, UNCLEAR }

    /** One or more citation markers at the start of a unit. */
    private static final Pattern LEADING_MARKERS = Pattern.compile("(?:\\[[\\d,\\s]+]\\s*)+");

    @Autowired
    IngestionPipeline pipeline;

    @Autowired
    RetrievalService retrievalService;

    @Autowired
    RagService ragService;

    @Autowired
    ChatModel judge;

    /** A sentence or list item from an answer, and the chunk numbers it cites. */
    record Claim(String text, List<Integer> cited) {

        /** A citation with nothing to check: a file name, a lone marker. */
        boolean bare() {
            return text.isBlank() || text.split("\\s+").length < MIN_CLAIM_WORDS;
        }

        /** "Based on [1], here are the steps:" — the claims are in the list that follows. */
        boolean leadIn() {
            return text.endsWith(":");
        }
    }

    record Pair(String question, Claim claim, int number, String file, String passage,
                double similarity, Verdict verdict) {
    }

    record SetupResult(String name, int answers, int claims, int bare, int leadIns, int invalidNumbers,
                       int uncitedSentences, List<Pair> pairs) {

        long count(Verdict v) {
            return pairs.stream().filter(p -> p.verdict() == v).count();
        }

        double avgSimilarity(Verdict v) {
            return pairs.stream().filter(p -> p.verdict() == v)
                    .mapToDouble(Pair::similarity).average().orElse(Double.NaN);
        }
    }

    @Test
    void checkCitations() throws IOException {
        List<String> questions = loadOnTopicQuestions();
        List<Document> documents = pipeline.loadDocuments();
        double floor = retrievalService.minScore();

        var chunks = new InMemoryEmbeddingStore<TextSegment>();
        pipeline.embedInto(pipeline.split(documents, 500, 50), chunks);
        SetupResult rag = run("RAG 500/50", questions,
                q -> retrievalService.search(retrievalService.embedQuery(q), K, chunks, floor));

        // Whole documents, embedded too, so the similarity check works the same way.
        List<TextSegment> wholeDocs = documents.stream()
                .map(d -> TextSegment.from(d.text(), d.metadata()))
                .toList();
        List<Embedding> docEmbeddings = pipeline.embedInto(wholeDocs, new InMemoryEmbeddingStore<>());
        List<EmbeddingMatch<TextSegment>> allDocs = IntStream.range(0, wholeDocs.size())
                .mapToObj(i -> new EmbeddingMatch<>(1.0, "doc-" + i, docEmbeddings.get(i), wholeDocs.get(i)))
                .toList();
        SetupResult everything = run("All docs", questions, q -> allDocs);

        String report = report(List.of(rag, everything));
        System.out.println(report);
        Path out = Path.of("build", "eval", "citation-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private SetupResult run(String name, List<String> questions,
                            Function<String, List<EmbeddingMatch<TextSegment>>> context) {
        int claimCount = 0, bare = 0, leadIns = 0, invalid = 0, uncited = 0;
        List<Pair> pairs = new ArrayList<>();
        for (int q = 0; q < questions.size(); q++) {
            String question = questions.get(q);
            System.out.printf("[%s] %d/%d %s%n", name, q + 1, questions.size(), question);
            List<EmbeddingMatch<TextSegment>> matches = context.apply(question);
            String answer = ragService.answer(question, matches).answer();

            for (Claim claim : claims(answer)) {
                if (claim.cited().isEmpty()) {
                    if (claim.text().split("\\s+").length >= 6) {
                        uncited++;
                    }
                    continue;
                }
                claimCount++;
                if (claim.bare()) {
                    bare++;
                    continue;
                }
                if (claim.leadIn()) {
                    leadIns++;
                    continue;
                }
                Embedding claimEmbedding = retrievalService.embedQuery(claim.text());
                for (int n : claim.cited()) {
                    if (n < 1 || n > matches.size()) {
                        invalid++;
                        continue;
                    }
                    EmbeddingMatch<TextSegment> chunk = matches.get(n - 1);
                    double similarity = RelevanceScore.fromCosineSimilarity(
                            CosineSimilarity.between(claimEmbedding, chunk.embedding()));
                    pairs.add(new Pair(question, claim, n, chunk.embedded().metadata().getString("file_name"),
                            chunk.embedded().text(), similarity, judge(chunk.embedded().text(), claim.text())));
                }
            }
        }
        return new SetupResult(name, questions.size(), claimCount, bare, leadIns, invalid, uncited, pairs);
    }

    private Verdict judge(String passage, String statement) {
        String reply = judge.chat(List.of(
                        SystemMessage.from(JUDGE_RULES),
                        UserMessage.from("PASSAGE:\n" + passage + "\n\nSTATEMENT:\n" + statement)))
                .aiMessage().text().toUpperCase(Locale.ROOT);
        // UNSUPPORTED contains SUPPORTED, so check it first.
        if (reply.contains("UNSUPPORTED")) return Verdict.UNSUPPORTED;
        if (reply.contains("PARTIAL")) return Verdict.PARTIAL;
        if (reply.contains("SUPPORTED")) return Verdict.SUPPORTED;
        return Verdict.UNCLEAR;
    }

    /**
     * Splits an answer into lines, then sentences. Markers right after a
     * sentence's full stop — "… adapter. [1] Next …" — belong to that
     * sentence; markers opening a line — "[1] The buyer …" — point forward.
     */
    static List<Claim> claims(String answer) {
        List<String> units = new ArrayList<>();
        for (String line : answer.split("\\R+")) {
            // A sentence ends with a letter or closing bracket plus . ! or ? —
            // so the "1." of a numbered list is not a sentence of its own.
            String[] sentences = line.split("(?<=[\\p{L}\\])\"'][.!?])\\s+");
            for (int i = 0; i < sentences.length; i++) {
                String unit = sentences[i].strip();
                Matcher leading = LEADING_MARKERS.matcher(unit);
                if (i > 0 && leading.lookingAt() && !units.isEmpty()) {
                    units.set(units.size() - 1, units.getLast() + " " + leading.group().strip());
                    unit = unit.substring(leading.end()).strip();
                }
                if (unit.isEmpty()) {
                    continue;
                }
                boolean onlyMarkers = RagService.CITATION.matcher(unit).replaceAll("").replaceAll("[\\s.,;:]", "").isEmpty();
                if (onlyMarkers && !units.isEmpty()) {
                    units.set(units.size() - 1, units.getLast() + " " + unit);
                } else {
                    units.add(unit);
                }
            }
        }
        List<Claim> claims = new ArrayList<>();
        for (String unit : units) {
            List<Integer> cited = new ArrayList<>();
            Matcher m = RagService.CITATION.matcher(unit);
            while (m.find()) {
                for (String digits : m.group(1).split("[,\\s]+")) {
                    if (!digits.isEmpty() && digits.length() <= 3 && !cited.contains(Integer.parseInt(digits))) {
                        cited.add(Integer.parseInt(digits));
                    }
                }
            }
            String text = RagService.CITATION.matcher(unit).replaceAll("")
                    .replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "")   // a list bullet or "1." / "1)"
                    .replaceAll("\\s+", " ").strip();
            claims.add(new Claim(text, cited));
        }
        return claims;
    }

    private static String report(List<SetupResult> results) {
        var sb = new StringBuilder("# Citation evaluation\n\n")
                .append("A *claim* is a sentence or list item carrying citation markers; each (claim, cited chunk) pair ")
                .append("is judged. *Similarity* is the claim's embedding against the chunk's, on the same (cosine + 1) / 2 ")
                .append("scale as retrieval.\n\n")
                .append("*Bare* citations have nothing to check (a file name, a lone marker); *lead-ins* end with a colon ")
                .append("and introduce a list. Neither is judged.\n\n")
                .append("| Setup | Cited claims | Bare | Lead-ins | Invalid numbers | Uncited sentences | Pairs judged | Supported | Partial | Unsupported | Unclear |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (SetupResult r : results) {
            int n = r.pairs().size();
            sb.append(format("| %s | %d | %d | %d | %d | %d | %d | %s | %s | %s | %d |%n",
                    r.name(), r.claims(), r.bare(), r.leadIns(), r.invalidNumbers(), r.uncitedSentences(), n,
                    share(r.count(Verdict.SUPPORTED), n), share(r.count(Verdict.PARTIAL), n),
                    share(r.count(Verdict.UNSUPPORTED), n), r.count(Verdict.UNCLEAR)));
        }

        sb.append("\n### Does cheap similarity track the judge?\n\nAverage similarity per verdict.\n\n")
          .append("| Setup | Supported | Partial | Unsupported |\n|---|---|---|---|\n");
        for (SetupResult r : results) {
            sb.append(format("| %s | %s | %s | %s |%n", r.name(),
                    fmt(r.avgSimilarity(Verdict.SUPPORTED)), fmt(r.avgSimilarity(Verdict.PARTIAL)),
                    fmt(r.avgSimilarity(Verdict.UNSUPPORTED))));
        }

        sb.append("\n## Every pair\n\nFill in *Your verdict* for a sample — that is how far the judge can be trusted.\n\n")
          .append("| Setup | Question | Claim | Cited | Passage | Similarity | Judge | Your verdict |\n|---|---|---|---|---|---|---|---|\n");
        for (SetupResult r : results) {
            for (Pair p : r.pairs()) {
                sb.append(format("| %s | %s | %s | [%d] %s | %s | %.3f | %s |  |%n", r.name(), cell(p.question(), 60),
                        cell(p.claim().text(), 160), p.number(), p.file(), cell(p.passage(), 400),
                        p.similarity(), p.verdict()));
            }
        }
        return sb.toString();
    }

    private static String share(long count, int total) {
        return total == 0 ? "–" : format("%d (%.0f%%)", count, 100.0 * count / total);
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "–" : format("%.3f", value);
    }

    private static String cell(String text, int max) {
        String flat = text.replace("|", "\\|").replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** The on-topic questions from the answer set; off-topic ones have nothing to cite. */
    private static List<String> loadOnTopicQuestions() throws IOException {
        try (InputStream in = CitationEvaluation.class.getResourceAsStream("/eval/answer-questions.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("|"))
                    .filter(line -> !line.substring(line.indexOf('|') + 1).isBlank())
                    .map(line -> line.substring(0, line.indexOf('|')).strip())
                    .toList();
        }
    }
}
