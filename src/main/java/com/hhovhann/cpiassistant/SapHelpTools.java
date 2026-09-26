package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * The fallback when the local docs do not answer: the official SAP
 * Integration Suite documentation, read page by page and kept.
 * <p>
 * {@code searchSapHelp} finds candidate pages in {@link SapHelpCatalog};
 * {@code readSapHelpPage} downloads one, splits and embeds it like the local
 * docs, and saves it in the vector store tagged {@code source=sap-help} with
 * its URL and fetch time. The next question on the same topic finds those
 * chunks through {@code searchCpiDocs} — no download. A saved page older than
 * {@code cpi.sap-help.max-age} is downloaded again.
 * <p>
 * What gets saved is SAP's text, never the model's answer: a wrong answer
 * stored as knowledge would come back, with a citation, on every later question.
 */
@Component
public class SapHelpTools {

    static final String SAP_HELP = "sap-help";
    static final String URL = "url";
    static final String TITLE = "title";
    static final String FETCHED_AT = "fetched_at";
    static final int MAX_PAGES = 5;
    static final int MAX_PASSAGES = 3;

    private final SapHelpCatalog catalog;
    private final SapHelpClient client;
    private final IngestionPipeline pipeline;
    private final RetrievalService retrievalService;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public SapHelpTools(SapHelpCatalog catalog, SapHelpClient client, IngestionPipeline pipeline,
                        RetrievalService retrievalService, EmbeddingStore<TextSegment> embeddingStore,
                        @Value("${cpi.sap-help.max-age:30d}") Duration maxAge) {
        this(catalog, client, pipeline, retrievalService, embeddingStore, maxAge, Clock.systemUTC());
    }

    SapHelpTools(SapHelpCatalog catalog, SapHelpClient client, IngestionPipeline pipeline,
                 RetrievalService retrievalService, EmbeddingStore<TextSegment> embeddingStore,
                 Duration maxAge, Clock clock) {
        this.catalog = catalog;
        this.client = client;
        this.pipeline = pipeline;
        this.retrievalService = retrievalService;
        this.embeddingStore = embeddingStore;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Tool("""
            Finds pages in the official SAP Integration Suite documentation (SAP Help) \
            by topic, and returns their titles and page ids. Use it only when \
            searchCpiDocs did not answer the question. Then call readSapHelpPage with \
            the id of the most relevant page.""")
    public String searchSapHelp(@P("The topic: a few keywords, e.g. 'SFTP receiver adapter'") String query) {
        List<SapHelpCatalog.Page> pages = catalog.search(query, MAX_PAGES);
        if (pages.isEmpty()) {
            return "No SAP Help pages found for: " + query;
        }
        return "SAP Help pages for '" + query + "':\n" + pages.stream()
                .map(page -> "- " + page.title() + " | page id: " + page.id())
                .collect(Collectors.joining("\n"));
    }

    @Tool("""
            Reads one SAP Help page and returns the passages most relevant to the \
            question, labelled with the page URL — cite that URL. The page is saved, \
            so later questions find it with searchCpiDocs. Needs a page id from \
            searchSapHelp.""")
    public String readSapHelpPage(@P("The page id, exactly as searchSapHelp returned it") String pageId,
                                  @P("The question to answer from the page") String question) {
        Optional<SapHelpCatalog.Page> found = catalog.page(pageId);
        if (found.isEmpty()) {
            return "Unknown page id " + pageId + ". Use a page id from searchSapHelp.";
        }
        SapHelpCatalog.Page page = found.get();
        Filter thisPage = metadataKey(URL).isEqualTo(page.url());

        List<EmbeddingMatch<TextSegment>> passages = retrievalService.searchWithin(question, MAX_PASSAGES, thisPage);
        String origin = "saved";
        if (passages.isEmpty() || isStale(passages.getFirst().embedded())) {
            Optional<String> text = client.fetch(page.path());
            if (text.isEmpty()) {
                return "The SAP Help page " + page.title() + " is no longer available at " + page.url() + ".";
            }
            save(page, text.get(), thisPage);
            passages = retrievalService.searchWithin(question, MAX_PASSAGES, thisPage);
            origin = "downloaded now";
        }
        return "From SAP Help, " + page.title() + " (" + origin + "):\n" + passages.stream()
                .map(match -> "[" + page.url() + "]\n" + match.embedded().text())
                .collect(Collectors.joining("\n---\n"));
    }

    private boolean isStale(TextSegment segment) {
        Long fetchedAt = segment.metadata().getLong(FETCHED_AT);
        return fetchedAt == null || Instant.ofEpochMilli(fetchedAt).plus(maxAge).isBefore(clock.instant());
    }

    /** Replaces any earlier copy of the page, then stores its chunks like the local docs. */
    private void save(SapHelpCatalog.Page page, String text, Filter thisPage) {
        Metadata metadata = new Metadata()
                .put(IngestionPipeline.SOURCE, SAP_HELP)
                .put(URL, page.url())
                .put(TITLE, page.title())
                .put(FETCHED_AT, clock.instant().toEpochMilli());
        List<TextSegment> segments = pipeline.split(List.of(Document.from(text, metadata)));
        embeddingStore.removeAll(thisPage);
        pipeline.embedInto(segments, embeddingStore);
    }
}
