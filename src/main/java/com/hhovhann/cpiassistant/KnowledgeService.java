package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Finds documentation for a question. One source of truth: the official SAP
 * Integration Suite docs, kept in the vector store. No model call.
 * <ol>
 *   <li>The best page of {@link SapHelpCatalog} for the question — if it
 *       matches at least {@code cpi.knowledge.min-title-score} — is made sure
 *       to be saved: downloaded ({@link SapHelpClient}) the first time, from the
 *       store after that. Its best passages go to the answer: the page chosen
 *       for the question, whatever else ranks high. "Capital of France"
 *       matches no page that well, and nothing is downloaded.</li>
 *   <li>The best passages from the whole store, at or above the score floor.</li>
 * </ol>
 * What is saved is SAP's text, never a model's answer; a saved page older than
 * {@code max-age} is downloaded again.
 */
@Service
public class KnowledgeService {

    static final String SOURCE = "source";
    static final String SAP_HELP = "sap-help";
    static final String URL = "url";
    static final String TITLE = "title";
    static final String FETCHED_AT = "fetched_at";
    /** Passages from the best page, and from the whole store. */
    static final int PAGE_PASSAGES = 3;
    static final int STORE_PASSAGES = 3;

    /** What {@link #find} did, step by step, and what it found. */
    public record Found(List<EmbeddingMatch<TextSegment>> passages, List<String> steps, List<String> downloaded) {
    }

    private final RetrievalService retrieval;
    private final SapHelpCatalog catalog;
    private final SapHelpClient client;
    private final IngestionPipeline pipeline;
    private final EmbeddingStore<TextSegment> store;
    private final double minTitleScore;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public KnowledgeService(RetrievalService retrieval, SapHelpCatalog catalog, SapHelpClient client,
                            IngestionPipeline pipeline, EmbeddingStore<TextSegment> store,
                            @Value("${cpi.knowledge.min-title-score:0.82}") double minTitleScore,
                            @Value("${cpi.knowledge.max-age:30d}") Duration maxAge) {
        this(retrieval, catalog, client, pipeline, store, minTitleScore, maxAge, Clock.systemUTC());
    }

    KnowledgeService(RetrievalService retrieval, SapHelpCatalog catalog, SapHelpClient client,
                     IngestionPipeline pipeline, EmbeddingStore<TextSegment> store,
                     double minTitleScore, Duration maxAge, Clock clock) {
        this.retrieval = retrieval;
        this.catalog = catalog;
        this.client = client;
        this.pipeline = pipeline;
        this.store = store;
        this.minTitleScore = minTitleScore;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** The best SAP page for the question (saved on first use), then the best passages overall. */
    public Found find(String query) {
        List<String> steps = new ArrayList<>();
        List<String> downloaded = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> passages = new ArrayList<>();

        Optional<SapHelpCatalog.PageMatch> best = catalog.search(query, 1).stream()
                .filter(match -> match.score() >= minTitleScore)
                .findFirst();
        if (best.isEmpty()) {
            steps.add("SAP Help: no page matches well enough (needs %.2f)".formatted(minTitleScore));
        } else {
            SapHelpCatalog.Page page = best.get().page();
            Saved saved = ensureSaved(page);
            steps.add(switch (saved) {
                case DOWNLOADED -> "SAP Help: downloaded \"%s\" (match %.3f) and saved it".formatted(page.title(), best.get().score());
                case ALREADY_SAVED -> "SAP Help: best page \"%s\" (match %.3f), already saved".formatted(page.title(), best.get().score());
                case MISSING -> "SAP Help: \"%s\" is no longer available".formatted(page.title());
            });
            if (saved == Saved.DOWNLOADED) {
                downloaded.add(page.title());
            }
            if (saved != Saved.MISSING) {
                passages.addAll(retrieval.searchWithin(query, PAGE_PASSAGES, metadataKey(URL).isEqualTo(page.url())));
            }
        }

        List<EmbeddingMatch<TextSegment>> fromStore = retrieval.search(query, STORE_PASSAGES);
        steps.add(fromStore.isEmpty()
                ? "Database: nothing above the %.2f floor".formatted(retrieval.minScore())
                : "Database: %d passage(s), best %.3f".formatted(fromStore.size(), fromStore.getFirst().score()));
        for (EmbeddingMatch<TextSegment> match : fromStore) {
            if (passages.stream().noneMatch(p -> p.embeddingId().equals(match.embeddingId()))) {
                passages.add(match);
            }
        }
        return new Found(passages, steps, downloaded);
    }

    public enum Saved { DOWNLOADED, ALREADY_SAVED, MISSING }

    /** Downloads and saves the page unless a fresh copy is already stored. */
    Saved ensureSaved(SapHelpCatalog.Page page) {
        Filter thisPage = metadataKey(URL).isEqualTo(page.url());
        List<EmbeddingMatch<TextSegment>> stored = retrieval.searchWithin(page.title(), 1, thisPage);
        if (!stored.isEmpty() && !isStale(stored.getFirst().embedded())) {
            return Saved.ALREADY_SAVED;
        }
        return client.fetch(page.path())
                .map(text -> {
                    save(page, text, thisPage);
                    return Saved.DOWNLOADED;
                })
                .orElse(Saved.MISSING);
    }

    private boolean isStale(TextSegment segment) {
        Long fetchedAt = segment.metadata().getLong(FETCHED_AT);
        return fetchedAt == null || Instant.ofEpochMilli(fetchedAt).plus(maxAge).isBefore(clock.instant());
    }

    /** Replaces any earlier copy of the page, then stores its chunks. */
    private void save(SapHelpCatalog.Page page, String text, Filter thisPage) {
        Metadata metadata = new Metadata()
                .put(SOURCE, SAP_HELP)
                .put(URL, page.url())
                .put(TITLE, page.title())
                .put(FETCHED_AT, clock.instant().toEpochMilli());
        List<TextSegment> segments = pipeline.split(List.of(Document.from(text, metadata)));
        store.removeAll(thisPage);
        pipeline.embedInto(segments, store);
    }

    /** How a passage is labelled for the model and cited back: the page title. */
    public static String label(TextSegment segment) {
        String title = segment.metadata().getString(TITLE);
        return title != null ? title : "unknown source";
    }

    /** The passages as the model sees them: each under its page title. */
    public static String format(List<EmbeddingMatch<TextSegment>> passages) {
        if (passages.isEmpty()) {
            return "(no documentation found)";
        }
        return String.join("\n---\n", passages.stream()
                .map(match -> "[" + label(match.embedded()) + "]\n" + match.embedded().text())
                .toList());
    }
}
