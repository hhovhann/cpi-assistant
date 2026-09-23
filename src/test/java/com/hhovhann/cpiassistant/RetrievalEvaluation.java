package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * Retrieval evaluation: for a fixed set of questions with known answers, does
 * the right document come back — and at which chunk size does that work best?
 * <p>
 * Not a unit test. It needs LM Studio serving the embedding model, so it is
 * tagged "eval", skipped by {@code ./gradlew test}, and run with
 * {@code ./gradlew eval}. No chat model is involved: only embeddings, so a
 * full sweep takes seconds.
 * <p>
 * For each chunk-size configuration it builds a fresh in-memory store through
 * the app's own IngestionPipeline and RetrievalService, then scores every
 * question. The headline metrics apply the same score floor as /ask, so they
 * describe what the model actually receives. The report goes to the console
 * and to build/eval/retrieval-report.md.
 */
@Tag("eval")
@SpringBootTest(properties = "cpi.ingestion.run-on-startup=false")
class RetrievalEvaluation {

    /** (maxSegmentSize, maxOverlapSize) pairs to compare. Overlap is 10% throughout. */
    private static final int[][] CONFIGS = {{200, 20}, {300, 30}, {500, 50}, {800, 80}, {1200, 120}, {2000, 200}};

    /** RagService sends the top K chunks to the model. */
    private static final int K = 3;

    /** How deep to look. Deeper than K so MRR and the rank table can show near misses. */
    private static final int DEPTH = 5;

    @Autowired
    IngestionPipeline pipeline;

    @Autowired
    RetrievalService retrievalService;

    record Question(String text, List<String> acceptedFiles) {
        boolean offTopic() {
            return acceptedFiles.isEmpty();
        }
    }

    /**
     * One question's outcome under one configuration.
     *
     * @param rank           1-based rank of the first chunk from an accepted file, ignoring the floor
     * @param rankAboveFloor the same, counting only chunks that clear the floor — what /ask would use
     */
    record Outcome(Question question, double topScore, OptionalInt rank, OptionalInt rankAboveFloor) {

        static Outcome of(Question question, List<EmbeddingMatch<TextSegment>> top, double floor) {
            OptionalInt rank = IntStream.range(0, top.size())
                    .filter(i -> question.acceptedFiles().contains(file(top.get(i))))
                    .map(i -> i + 1)
                    .findFirst();
            // Results are sorted by score, so the ones above the floor are a prefix:
            // the rank stays the same, it just has to land inside that prefix.
            boolean aboveFloor = rank.isPresent() && top.get(rank.getAsInt() - 1).score() >= floor;
            return new Outcome(question,
                    top.isEmpty() ? 0 : top.getFirst().score(),
                    rank,
                    aboveFloor ? rank : OptionalInt.empty());
        }

        boolean hitAt(int k) {
            return rankAboveFloor.orElse(Integer.MAX_VALUE) <= k;
        }
    }

    record ConfigResult(int size, int overlap, int segments, long embedMillis, List<Outcome> outcomes) {

        List<Outcome> onTopic() {
            return outcomes.stream().filter(o -> !o.question().offTopic()).toList();
        }

        List<Outcome> offTopic() {
            return outcomes.stream().filter(o -> o.question().offTopic()).toList();
        }

        /** Share of on-topic questions with an accepted file in the top k, above the floor. */
        double hitAt(int k) {
            List<Outcome> onTopic = onTopic();
            return onTopic.isEmpty() ? 0 : onTopic.stream().filter(o -> o.hitAt(k)).count() / (double) onTopic.size();
        }

        /**
         * Mean reciprocal rank: 1 for a hit at rank 1, 1/2 at rank 2, … 0 for
         * no hit above the floor. One number that rewards putting the right
         * chunk first.
         */
        double mrr() {
            return onTopic().stream()
                    .mapToDouble(o -> o.rankAboveFloor().isPresent() ? 1.0 / o.rankAboveFloor().getAsInt() : 0)
                    .average().orElse(0);
        }

        /** On-topic questions whose best chunk falls below the floor — they would get "I don't know". */
        long blockedByFloor(double floor) {
            return onTopic().stream().filter(o -> o.topScore() < floor).count();
        }

        /** Off-topic questions whose best chunk clears the floor — they would get unrelated context. */
        long leakedPastFloor(double floor) {
            return offTopic().stream().filter(o -> o.topScore() >= floor).count();
        }
    }

    @Test
    void sweepChunkSizes() throws IOException {
        List<Document> documents = pipeline.loadDocuments();
        Set<String> knownFiles = documents.stream()
                .map(d -> d.metadata().getString("file_name"))
                .collect(Collectors.toSet());
        List<Question> questions = loadQuestions(knownFiles);
        double floor = retrievalService.minScore();

        // Also a warm-up: the first call makes LM Studio load the model, which
        // would otherwise be billed to the first configuration's timing.
        List<Embedding> questionEmbeddings = questions.stream()
                .map(q -> retrievalService.embedQuery(q.text()))
                .toList();

        List<ConfigResult> results = new ArrayList<>();
        for (int[] config : CONFIGS) {
            results.add(evaluate(config[0], config[1], documents, questions, questionEmbeddings, floor));
        }

        String report = report(results, questions, floor);
        System.out.println(report);
        Path out = Path.of("build", "eval", "retrieval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private ConfigResult evaluate(int size, int overlap, List<Document> documents, List<Question> questions,
                                  List<Embedding> questionEmbeddings, double floor) {
        List<TextSegment> segments = pipeline.split(documents, size, overlap);
        var store = new InMemoryEmbeddingStore<TextSegment>();

        long start = System.nanoTime();
        pipeline.embedInto(segments, store);
        long embedMillis = (System.nanoTime() - start) / 1_000_000;

        List<Outcome> outcomes = IntStream.range(0, questions.size())
                .mapToObj(i -> Outcome.of(questions.get(i),
                        retrievalService.search(questionEmbeddings.get(i), DEPTH, store, 0.0), floor))
                .toList();
        return new ConfigResult(size, overlap, segments.size(), embedMillis, outcomes);
    }

    private static String report(List<ConfigResult> results, List<Question> questions, double floor) {
        var sb = new StringBuilder();
        long onTopic = questions.stream().filter(q -> !q.offTopic()).count();
        sb.append("# Retrieval evaluation\n\n")
          .append(format("%d on-topic questions, %d off-topic. Score floor %.2f, applied as /ask applies it: ",
                  onTopic, questions.size() - onTopic, floor))
          .append(format("Hit@k = an accepted file in the top k *and* above the floor.%n%n"))
          .append(format("| Chunks (size/overlap) | Segments | Embed ms | Hit@1 | Hit@%d | MRR@%d | Blocked by floor | Off-topic leaked |%n", K, DEPTH))
          .append("|---|---|---|---|---|---|---|---|\n");
        for (ConfigResult r : results) {
            sb.append(format("| %d/%d | %d | %d | %.0f%% | %.0f%% | %.3f | %d | %d |%n",
                    r.size(), r.overlap(), r.segments(), r.embedMillis(),
                    100 * r.hitAt(1), 100 * r.hitAt(K), r.mrr(),
                    r.blockedByFloor(floor), r.leakedPastFloor(floor)));
        }

        sb.append("\n## Rank of the first correct chunk, per question\n\n")
          .append(format("`–` = not in the top %d. `*` = found, but below the floor, so /ask never sees it. ", DEPTH))
          .append("Off-topic rows show the best score instead; below the floor is the right outcome.\n\n| Question |");
        results.forEach(r -> sb.append(" ").append(r.size()).append("/").append(r.overlap()).append(" |"));
        sb.append("\n|---|").append("---|".repeat(results.size())).append("\n");
        for (int q = 0; q < questions.size(); q++) {
            Question question = questions.get(q);
            sb.append("| ").append(question.offTopic() ? "*(off-topic)* " : "").append(question.text()).append(" |");
            for (ConfigResult r : results) {
                Outcome o = r.outcomes().get(q);
                String cell;
                if (question.offTopic()) {
                    cell = format("%.3f%s", o.topScore(), o.topScore() >= floor ? " ⚠" : "");
                } else if (o.rank().isEmpty()) {
                    cell = "–";
                } else {
                    cell = o.rank().getAsInt() + (o.rankAboveFloor().isEmpty() ? "*" : "");
                }
                sb.append(" ").append(cell).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static List<Question> loadQuestions(Set<String> knownFiles) throws IOException {
        try (InputStream in = RetrievalEvaluation.class.getResourceAsStream("/eval/retrieval-questions.txt")) {
            List<Question> questions = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> parse(line, knownFiles))
                    .toList();
            if (questions.stream().allMatch(Question::offTopic)) {
                throw new IllegalStateException("retrieval-questions.txt has no on-topic questions to score");
            }
            return questions;
        }
    }

    private static Question parse(String line, Set<String> knownFiles) {
        int bar = line.indexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("Missing '|' in retrieval-questions.txt: " + line);
        }
        String files = line.substring(bar + 1).strip();
        List<String> accepted = files.isEmpty() ? List.of() : Arrays.stream(files.split(",")).map(String::strip).toList();
        // A typo would silently turn the question into a permanent miss.
        accepted.stream().filter(f -> !knownFiles.contains(f)).findFirst().ifPresent(f -> {
            throw new IllegalArgumentException("Unknown file '" + f + "' in retrieval-questions.txt: " + line);
        });
        return new Question(line.substring(0, bar).strip(), accepted);
    }

    private static String file(EmbeddingMatch<TextSegment> match) {
        return match.embedded().metadata().getString("file_name");
    }
}
