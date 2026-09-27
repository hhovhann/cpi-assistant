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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Finds documentation for a question. One source of truth: the official SAP
 * Integration Suite docs, kept in the vector store.
 * <ol>
 *   <li>Search the store. Chunks at or above the score floor: done.</li>
 *   <li>Otherwise look the question up in {@link SapHelpCatalog}. Pages whose
 *       title matches at least {@code cpi.knowledge.min-title-score} are
 *       downloaded ({@link SapHelpClient}) and saved; below it, nothing is —
 *       "capital of France" matches no title that well.</li>
 *   <li>Search the store again.</li>
 * </ol>
 * The next question on the same topic stops at step 1. What is saved is SAP's
 * text, never a model's answer; a saved page older than {@code max-age} is
 * downloaded again.
 * <p>
 * Then one hop through the {@link PageGraph}: if a passage mentions a page its
 * own page links to ("see Configure JDBC Drivers"), the best such page is read
 * too — if it matches the question as well as a download would have to. That
 * is where prerequisites and details live, which similarity alone did not
 * reach. No model call anywhere in here.
 */
@Service
public class KnowledgeService {

    static final String SOURCE = "source";
    static final String SAP_HELP = "sap-help";
    static final String URL = "url";
    static final String TITLE = "title";
    static final String FETCHED_AT = "fetched_at";
    static final int MAX_PASSAGES = 3;

    /** What {@link #find} did, step by step, and what it found. */
    public record Found(List<EmbeddingMatch<TextSegment>> passages, List<String> steps, List<String> downloaded) {
    }

    private final RetrievalService retrieval;
    private final SapHelpCatalog catalog;
    private final SapHelpClient client;
    private final PageGraph graph;
    private final boolean followLinks;
    private final IngestionPipeline pipeline;
    private final EmbeddingStore<TextSegment> store;
    private final double minTitleScore;
    private final int maxPagesPerMiss;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public KnowledgeService(RetrievalService retrieval, SapHelpCatalog catalog, SapHelpClient client, PageGraph graph,
                            IngestionPipeline pipeline, EmbeddingStore<TextSegment> store,
                            @Value("${cpi.knowledge.min-title-score:0.82}") double minTitleScore,
                            @Value("${cpi.knowledge.max-pages-per-miss:1}") int maxPagesPerMiss,
                            @Value("${cpi.knowledge.max-age:30d}") Duration maxAge,
                            @Value("${cpi.knowledge.follow-links:true}") boolean followLinks) {
        this(retrieval, catalog, client, graph, pipeline, store, minTitleScore, maxPagesPerMiss, maxAge, followLinks, Clock.systemUTC());
    }

    KnowledgeService(RetrievalService retrieval, SapHelpCatalog catalog, SapHelpClient client, PageGraph graph,
                     IngestionPipeline pipeline, EmbeddingStore<TextSegment> store,
                     double minTitleScore, int maxPagesPerMiss, Duration maxAge, boolean followLinks, Clock clock) {
        this.retrieval = retrieval;
        this.catalog = catalog;
        this.client = client;
        this.graph = graph;
        this.followLinks = followLinks;
        this.pipeline = pipeline;
        this.store = store;
        this.minTitleScore = minTitleScore;
        this.maxPagesPerMiss = maxPagesPerMiss;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** The store first; SAP Help only on a miss. */
    public Found find(String query) {
        List<EmbeddingMatch<TextSegment>> passages = retrieval.search(query, MAX_PASSAGES);
        if (!passages.isEmpty()) {
            List<String> steps = new ArrayList<>(List.of(
                    "Database: %d passage(s), best %.3f".formatted(passages.size(), passages.getFirst().score())));
            return followLink(query, new Found(passages, steps, new ArrayList<>()));
        }
        List<String> steps = new ArrayList<>(List.of("Database: nothing above the %.2f floor".formatted(retrieval.minScore())));
        return followLink(query, fetchAndSearch(query, steps));
    }

    /**
     * SAP Help even though the store had passages — for when those passages
     * turned out not to answer the question.
     */
    public Found fetchFromSapHelp(String query) {
        return followLink(query, fetchAndSearch(query, new ArrayList<>()));
    }

    /**
     * One hop through the page graph. Candidates are pages that a passage's own
     * page links to <i>and</i> that the passage names — "see Configure JDBC
     * Drivers" — not every link on the page: a page links to a dozen
     * neighbours, and the closest by meaning was usually a sibling ("JDBC for
     * MariaDB"), not what the passage points to. The best candidate is read if
     * it matches the question at least {@code min-title-score}; its best
     * passage is added.
     */
    private Found followLink(String query, Found found) {
        if (!followLinks || found.passages().isEmpty()) {
            return found;
        }
        Set<String> current = new HashSet<>();
        List<SapHelpCatalog.Page> candidates = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> passage : found.passages()) {
            String url = passage.embedded().metadata().getString(URL);
            if (url != null) {
                current.add(SapHelpCatalog.Page.idFromUrl(url));
            }
        }
        for (EmbeddingMatch<TextSegment> passage : found.passages()) {
            String url = passage.embedded().metadata().getString(URL);
            if (url == null) {
                continue;
            }
            String text = passage.embedded().text().toLowerCase(Locale.ROOT);
            for (String target : graph.linksFrom(SapHelpCatalog.Page.idFromUrl(url))) {
                if (current.contains(target)) {
                    continue;
                }
                catalog.page(target)
                        .filter(page -> text.contains(page.title().toLowerCase(Locale.ROOT)))
                        .filter(page -> !candidates.contains(page))
                        .ifPresent(candidates::add);
            }
        }
        if (candidates.isEmpty()) {
            return found;
        }
        SapHelpCatalog.PageMatch best = catalog.score(query, candidates).getFirst();
        if (best.score() < minTitleScore) {
            return found;
        }
        SapHelpCatalog.Page page = best.page();
        Saved saved = ensureSaved(page);
        if (saved == Saved.MISSING) {
            return found;
        }
        List<EmbeddingMatch<TextSegment>> extra = retrieval.searchWithin(query, 1, metadataKey(URL).isEqualTo(page.url()));
        if (extra.isEmpty()) {
            return found;
        }
        List<String> steps = new ArrayList<>(found.steps());
        steps.add("Graph: a passage links to \"%s\" (match %.3f)%s — added its best passage"
                .formatted(page.title(), best.score(), saved == Saved.DOWNLOADED ? ", downloaded and saved" : ""));
        List<String> downloaded = new ArrayList<>(found.downloaded());
        if (saved == Saved.DOWNLOADED) {
            downloaded.add(page.title());
        }
        List<EmbeddingMatch<TextSegment>> passages = new ArrayList<>(found.passages());
        passages.add(extra.getFirst());
        return new Found(passages, steps, downloaded);
    }

    private Found fetchAndSearch(String query, List<String> steps) {
        List<SapHelpCatalog.PageMatch> candidates = catalog.search(query, maxPagesPerMiss).stream()
                .filter(match -> match.score() >= minTitleScore)
                .toList();
        if (candidates.isEmpty()) {
            steps.add("SAP Help: no page title matches well enough (needs %.2f)".formatted(minTitleScore));
            return new Found(List.of(), steps, List.of());
        }
        List<String> downloaded = new ArrayList<>();
        for (SapHelpCatalog.PageMatch candidate : candidates) {
            SapHelpCatalog.Page page = candidate.page();
            switch (ensureSaved(page)) {
                case DOWNLOADED -> {
                    downloaded.add(page.title());
                    steps.add("SAP Help: downloaded \"%s\" (title match %.3f) and saved it".formatted(page.title(), candidate.score()));
                }
                case ALREADY_SAVED -> steps.add("SAP Help: \"%s\" is already saved".formatted(page.title()));
                case MISSING -> steps.add("SAP Help: \"%s\" is no longer available".formatted(page.title()));
            }
        }
        List<EmbeddingMatch<TextSegment>> passages = retrieval.search(query, MAX_PASSAGES);
        steps.add("Database again: %d passage(s)".formatted(passages.size()));
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

    static boolean isIDontKnow(String answer) {
        return answer != null && answer.strip().toLowerCase(Locale.ROOT).startsWith("i don't know");
    }
}
