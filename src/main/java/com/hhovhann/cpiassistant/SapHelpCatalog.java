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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Pages are found by their title and summary — the summary is what lets
 * "AS4" find "Configure Receiver Channel with ebMS3 Push", whose title never
 * says AS4. The ~1,660 entries are embedded on the first search and kept in
 * memory: a few seconds, once per start. scripts/build_sap_help_catalog.py
 * rebuilds the file.
 */
@Component
public class SapHelpCatalog {

    static final String REPO_BLOB = "https://github.com/SAP-docs/btp-integration-suite/blob/main/";
    private static final int EMBED_BATCH = 256;

    /**
     * @param path    path in the repository, e.g. docs/.../define-exception-subprocess-690e078.md
     * @param title   the page heading, e.g. "Define Exception Subprocess"
     * @param summary the page's first sentence, SAP's short description; may be empty
     */
    public record Page(String path, String title, String summary) {

        public Page(String path, String title) {
            this(path, title, "");
        }

        /** What a question is matched against: the words of the title and of the summary. */
        String searchText() {
            return summary.isBlank() ? title : title + ". " + summary;
        }

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
                    .map(line -> line.split("\t", 3))
                    .map(parts -> new Page(parts[0], parts[1], parts.length > 2 ? parts[2] : ""))
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

    /** A catalog page and how well its title and summary match, on the (cosine + 1) / 2 scale. */
    public record PageMatch(Page page, double score) {
    }

    /** How many nearest pages {@link #rank} chooses from. */
    static final int CANDIDATES = 20;
    /** A "Configure …" page is preferred only if it matches nearly as well as the best page. */
    static final double CONFIGURE_MARGIN = 0.025;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern HOW_TO_CONFIGURE = Pattern.compile("(?i)\\b(configure|configuring|set ?up|setup)\\b");

    /** The best pages for the query, best first — nearest by meaning, then {@link #rank}ed. */
    public List<PageMatch> search(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(queryPrefix + query).content();
        List<PageMatch> nearest = titleIndex().search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding).maxResults(CANDIDATES).minScore(0.0).build())
                .matches().stream()
                .map(match -> new PageMatch(pagesById.get(match.embedded().metadata().getString("id")), match.score()))
                .toList();
        List<PageMatch> ranked = rank(query, nearest);
        return ranked.subList(0, Math.min(maxResults, ranked.size()));
    }

    /**
     * Two rules on top of "nearest by meaning", both measured on real questions:
     * <ol>
     *   <li><b>Identifiers must match exactly.</b> To the embedding model AS4 and
     *       AS2 are nearly the same word: "configure the AS4 receiver adapter"
     *       came closest to "Configure the AS2 Receiver Adapter". A word with a
     *       digit or two capitals — AS4, JDBC, SFTP, OData, V2 — is an identifier;
     *       pages whose title and summary lack one are dropped (unless that would
     *       drop them all).</li>
     *   <li><b>How-to questions prefer "Configure …" pages</b> — the ones with the
     *       steps — but only within {@link #CONFIGURE_MARGIN} of the best: "Configure
     *       JDBC Drivers" (0.876) is about drivers, not the adapter, and must not
     *       beat "JDBC Receiver Adapter" (0.909).</li>
     * </ol>
     * Scores stay the embedding scores, so the download threshold still means
     * the same thing.
     */
    static List<PageMatch> rank(String query, List<PageMatch> nearestFirst) {
        List<PageMatch> pool = nearestFirst;
        Set<String> identifiers = identifiers(query);
        if (!identifiers.isEmpty()) {
            List<PageMatch> exact = pool.stream().filter(m -> words(m.page().searchText()).containsAll(identifiers)).toList();
            if (!exact.isEmpty()) {
                pool = exact;
            }
        }
        if (HOW_TO_CONFIGURE.matcher(query).find() && !pool.isEmpty()) {
            double best = pool.getFirst().score();
            List<PageMatch> configure = pool.stream()
                    .filter(m -> m.page().title().startsWith("Configure") && best - m.score() <= CONFIGURE_MARGIN)
                    .toList();
            List<PageMatch> reordered = new ArrayList<>(configure);
            pool.stream().filter(m -> !configure.contains(m)).forEach(reordered::add);
            pool = reordered;
        }
        return pool;
    }

    /** Words with a digit or at least two capitals: AS4, JDBC, SFTP, OData, V2 — lower-cased. */
    static Set<String> identifiers(String text) {
        Set<String> found = new HashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (word.chars().anyMatch(Character::isDigit) || word.chars().filter(Character::isUpperCase).count() >= 2) {
                found.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return found;
    }

    private static Set<String> words(String text) {
        Set<String> found = new HashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            found.add(m.group().toLowerCase(Locale.ROOT));
        }
        return found;
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
                    .map(page -> TextSegment.from(page.searchText(), Metadata.from("id", page.id())))
                    .toList();
            List<TextSegment> prefixed = batch.stream()
                    .map(segment -> TextSegment.from(documentPrefix + segment.text(), segment.metadata()))
                    .toList();
            index.addAll(embeddingModel.embedAll(prefixed).content(), batch);
        }
        return index;
    }
}
