package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The curated list of SAP Help pages the agent may read:
 * {@code sap-help/catalog.tsv}, one line per page of the Integration Suite
 * documentation in github.com/SAP-docs/btp-integration-suite — the Markdown
 * source SAP publishes for help.sap.com, under CC BY 4.0.
 * <p>
 * Why a list and not the web: help.sap.com renders its pages with JavaScript
 * and its robots.txt disallows automated clients. The list also bounds what
 * the model can reach — it picks page ids from here, never free URLs.
 * <p>
 * Pages are found by their titles. The ~1,660 titles are embedded on the first
 * search and kept in memory: a few seconds, once per start.
 */
@Component
public class SapHelpCatalog {

    static final String REPO_BLOB = "https://github.com/SAP-docs/btp-integration-suite/blob/main/";
    private static final int EMBED_BATCH = 256;

    /**
     * @param path  path in the repository, e.g. docs/.../define-exception-subprocess-690e078.md
     * @param title from the file name, e.g. "Define Exception Subprocess"
     */
    public record Page(String path, String title) {

        /** The file name without .md — what the model passes back to read a page. */
        public String id() {
            String file = path.substring(path.lastIndexOf('/') + 1);
            return file.substring(0, file.length() - ".md".length());
        }

        /** Where a person can read the page; also the citation. */
        public String url() {
            return REPO_BLOB + path;
        }
    }

    private final Map<String, Page> pagesById;
    private final EmbeddingModel embeddingModel;
    private final String queryPrefix;
    private final String documentPrefix;
    private volatile InMemoryEmbeddingStore<TextSegment> titleIndex;

    @Autowired
    public SapHelpCatalog(EmbeddingModel embeddingModel,
                          @Value("${langchain4j.open-ai.embedding-model.query-prefix:}") String queryPrefix,
                          @Value("${langchain4j.open-ai.embedding-model.document-prefix:}") String documentPrefix) {
        this(load(), embeddingModel, queryPrefix, documentPrefix);
    }

    SapHelpCatalog(List<Page> pages, EmbeddingModel embeddingModel, String queryPrefix, String documentPrefix) {
        this.pagesById = new LinkedHashMap<>();
        pages.forEach(page -> pagesById.put(page.id(), page));
        this.embeddingModel = embeddingModel;
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
    }

    private static List<Page> load() {
        try {
            String tsv = new ClassPathResource("sap-help/catalog.tsv").getContentAsString(StandardCharsets.UTF_8);
            return tsv.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t", 2))
                    .map(parts -> new Page(parts[0], parts[1]))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sap-help/catalog.tsv", e);
        }
    }

    public int size() {
        return pagesById.size();
    }

    public Optional<Page> page(String id) {
        return Optional.ofNullable(id == null ? null : pagesById.get(id.trim()));
    }

    /** The first page with this exact title — to link a title the model was shown. */
    public Optional<Page> pageByTitle(String title) {
        return pagesById.values().stream().filter(page -> page.title().equals(title)).findFirst();
    }

    /** A catalog page and how well its title matches, on the (cosine + 1) / 2 scale. */
    public record PageMatch(Page page, double score) {
    }

    /** The pages whose titles are closest to the query, best first. */
    public List<PageMatch> search(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(queryPrefix + query).content();
        return titleIndex().search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding).maxResults(maxResults).minScore(0.0).build())
                .matches().stream()
                .map(match -> new PageMatch(pagesById.get(match.embedded().metadata().getString("id")), match.score()))
                .toList();
    }

    private InMemoryEmbeddingStore<TextSegment> titleIndex() {
        InMemoryEmbeddingStore<TextSegment> index = titleIndex;
        if (index == null) {
            synchronized (this) {
                index = titleIndex;
                if (index == null) {
                    index = buildTitleIndex();
                    titleIndex = index;
                }
            }
        }
        return index;
    }

    private InMemoryEmbeddingStore<TextSegment> buildTitleIndex() {
        var index = new InMemoryEmbeddingStore<TextSegment>();
        List<Page> pages = new ArrayList<>(pagesById.values());
        for (int from = 0; from < pages.size(); from += EMBED_BATCH) {
            List<TextSegment> batch = pages.subList(from, Math.min(from + EMBED_BATCH, pages.size())).stream()
                    .map(page -> TextSegment.from(page.title(), Metadata.from("id", page.id())))
                    .toList();
            List<TextSegment> prefixed = batch.stream()
                    .map(segment -> TextSegment.from(documentPrefix + segment.text(), segment.metadata()))
                    .toList();
            index.addAll(embeddingModel.embedAll(prefixed).content(), batch);
        }
        return index;
    }
}
