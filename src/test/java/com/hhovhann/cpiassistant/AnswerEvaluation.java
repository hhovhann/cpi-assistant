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
import java.util.Locale;
import java.util.function.Function;

import static java.lang.String.format;

/**
 * Answer evaluation: does RAG give better answers than putting every document
 * in the prompt — and which chunk size gives the best answers?
 * <p>
 * Three setups answer the same questions through the same prompt and citation
 * code (RagService.answer(question, matches)); only the context differs:
 * <ul>
 *   <li>RAG at 500/50 — the current default</li>
 *   <li>RAG at 300/30 — best retrieval in the 10a sweep</li>
 *   <li>All docs — every document whole, ~20K tokens, no retrieval</li>
 * </ul>
 * Each answer is checked for the key facts listed in answer-questions.txt.
 * Needs LM Studio with the chat model loaded with a context of at least
 * 32K tokens, or the all-docs prompt will not fit. Run with {@code ./gradlew eval}.
 * The report, including every answer, goes to build/eval/answer-report.md.
 */
@Tag("eval")
@SpringBootTest(properties = {
        "cpi.ingestion.run-on-startup=false",
        // A 20K-token prompt per request would bury the console.
        "cpi.chat.log-requests=false",
        "cpi.chat.log-responses=false"})
class AnswerEvaluation {

    private static final int K = 3;

    /** Short and neutral: warm-up calls exist for their side effects, not their answers. */
    private static final String WARM_UP = "Reply with OK.";

    @Autowired
    IngestionPipeline pipeline;

    @Autowired
    RetrievalService retrievalService;

    @Autowired
    RagService ragService;

    record Question(String text, List<List<String>> facts) {
        boolean offTopic() {
            return facts.isEmpty();
        }
    }

    record Graded(Question question, RagService.RagAnswer answer, int factsFound) {

        boolean saysDontKnow() {
            String a = answer.answer().toLowerCase(Locale.ROOT);
            return a.contains("don't know") || a.contains("do not know") || a.contains("don’t know");
        }

        /** On-topic: every fact present. Off-topic: declined. */
        boolean correct() {
            return question.offTopic() ? saysDontKnow() : factsFound == question.facts().size();
        }
    }

    record SetupResult(String name, List<Graded> graded) {

        List<Graded> onTopic() {
            return graded.stream().filter(g -> !g.question().offTopic()).toList();
        }

        double factCoverage() {
            return onTopic().stream()
                    .mapToDouble(g -> g.factsFound() / (double) g.question().facts().size())
                    .average().orElse(0);
        }

        long fullyCorrect() {
            return onTopic().stream().filter(Graded::correct).count();
        }

        long offTopicDeclined() {
            return graded.stream().filter(g -> g.question().offTopic() && g.correct()).count();
        }

        /** On-topic answers that wrongly said "I don't know". */
        long wrongDontKnow() {
            return onTopic().stream().filter(Graded::saysDontKnow).count();
        }

        double avg(Function<RagService.RagAnswer, Number> metric) {
            return graded.stream()
                    .map(g -> metric.apply(g.answer()))
                    .filter(n -> n != null)
                    .mapToDouble(Number::doubleValue)
                    .average().orElse(0);
        }
    }

    @Test
    void compareSetups() throws IOException {
        List<Question> questions = loadQuestions();
        List<Document> documents = pipeline.loadDocuments();
        double floor = retrievalService.minScore();
        List<Embedding> questionEmbeddings = questions.stream()
                .map(q -> retrievalService.embedQuery(q.text()))
                .toList();

        // Warm-up, untimed: the first request makes LM Studio load the model,
        // which would otherwise be billed to the first RAG answer.
        ragService.answer(WARM_UP, List.of());

        List<SetupResult> results = new ArrayList<>();
        for (int[] config : new int[][]{{500, 50}, {300, 30}}) {
            var store = new InMemoryEmbeddingStore<TextSegment>();
            pipeline.embedInto(pipeline.split(documents, config[0], config[1]), store);
            String name = format("RAG %d/%d", config[0], config[1]);
            results.add(run(name, questions, i ->
                    retrievalService.search(questionEmbeddings.get(i), K, store, floor)));
        }

        // Every document whole, numbered like chunks so the prompt and the
        // citation rule are identical. Score 1.0 is a placeholder: never shown.
        List<EmbeddingMatch<TextSegment>> allDocs = documents.stream()
                .map(d -> new EmbeddingMatch<>(1.0, d.metadata().getString("file_name"), null,
                        TextSegment.from(d.text(), d.metadata())))
                .toList();
        // The first call has to read the full ~16K-token prompt; LM Studio then
        // caches that shared prefix, and later calls only read the question.
        // Time the cold call separately so it doesn't distort the average.
        long coldStart = System.nanoTime();
        ragService.answer(WARM_UP, allDocs);
        double coldSeconds = (System.nanoTime() - coldStart) / 1e9;
        results.add(run("All docs", questions, i -> allDocs));

        String report = report(results, questions, coldSeconds);
        System.out.println(report);
        Path out = Path.of("build", "eval", "answer-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private SetupResult run(String name, List<Question> questions,
                            Function<Integer, List<EmbeddingMatch<TextSegment>>> context) {
        List<Graded> graded = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            System.out.printf("[%s] %d/%d %s%n", name, i + 1, questions.size(), question.text());
            RagService.RagAnswer answer = ragService.answer(question.text(), context.apply(i));
            String text = answer.answer().toLowerCase(Locale.ROOT);
            int found = (int) question.facts().stream()
                    .filter(alternatives -> alternatives.stream().anyMatch(a -> text.contains(a.toLowerCase(Locale.ROOT))))
                    .count();
            graded.add(new Graded(question, answer, found));
        }
        return new SetupResult(name, graded);
    }

    private static String report(List<SetupResult> results, List<Question> questions, double allDocsColdSeconds) {
        var sb = new StringBuilder();
        long onTopic = questions.stream().filter(q -> !q.offTopic()).count();
        long offTopic = questions.size() - onTopic;
        sb.append("# Answer evaluation\n\n")
          .append(format("%d on-topic questions, %d off-topic. Same model, same prompt; only the context differs.%n%n",
                  onTopic, offTopic))
          .append("| Setup | Facts found | All facts | Wrong \"I don't know\" | Off-topic declined | Avg input tokens | Avg output tokens | Avg seconds |\n")
          .append("|---|---|---|---|---|---|---|---|\n");
        for (SetupResult r : results) {
            sb.append(format("| %s | %.0f%% | %d/%d | %d | %d/%d | %.0f | %.0f | %.1f |%n",
                    r.name(), 100 * r.factCoverage(), r.fullyCorrect(), onTopic, r.wrongDontKnow(),
                    r.offTopicDeclined(), offTopic,
                    r.avg(RagService.RagAnswer::inputTokens), r.avg(RagService.RagAnswer::outputTokens),
                    r.avg(RagService.RagAnswer::millis) / 1000));
        }

        sb.append(format("%nAvg seconds are warm: the model is loaded first, untimed. The all-docs setup's "
                + "first (cold) call, which reads the full prompt before LM Studio can cache it, took %.1f s "
                + "and is not in its average.%n", allDocsColdSeconds));

        sb.append("\n## Answers\n\nFacts found / facts expected. Read these — keyword matching is only a first filter.\n");
        for (int q = 0; q < questions.size(); q++) {
            Question question = questions.get(q);
            sb.append("\n### ").append(question.text()).append("\n\n");
            sb.append(question.offTopic() ? "*Off-topic — expected: I don't know.*\n"
                    : "*Facts:* " + question.facts().stream().map(f -> String.join(" / ", f)).toList() + "\n");
            for (SetupResult r : results) {
                Graded g = r.graded().get(q);
                String verdict = question.offTopic()
                        ? (g.correct() ? "declined ✓" : "answered ✗")
                        : g.factsFound() + "/" + question.facts().size() + (g.correct() ? " ✓" : "");
                sb.append("\n**").append(r.name()).append("** — ").append(verdict)
                  .append(" · cited ").append(g.answer().sources().stream().map(RagService.Source::file).toList())
                  .append("\n\n> ").append(g.answer().answer().strip().replace("\n", "\n> ")).append("\n");
            }
        }
        return sb.toString();
    }

    private static List<Question> loadQuestions() throws IOException {
        try (InputStream in = AnswerEvaluation.class.getResourceAsStream("/eval/answer-questions.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(AnswerEvaluation::parse)
                    .toList();
        }
    }

    private static Question parse(String line) {
        int bar = line.indexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("Missing '|' in answer-questions.txt: " + line);
        }
        String facts = line.substring(bar + 1).strip();
        List<List<String>> parsed = facts.isEmpty() ? List.of() : Arrays.stream(facts.split(";"))
                .map(fact -> Arrays.stream(fact.split("/")).map(String::strip).filter(s -> !s.isEmpty()).toList())
                .toList();
        return new Question(line.substring(0, bar).strip(), parsed);
    }
}
