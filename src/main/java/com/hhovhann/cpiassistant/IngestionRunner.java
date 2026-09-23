package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

import static java.lang.String.format;

/**
 * Prints loading + chunking stats on startup so we can see the effect of
 * different splitting strategies without writing tests yet.
 * <p>
 * Needs LM Studio running. Tests switch it off with
 * cpi.ingestion.run-on-startup=false so they don't depend on a live model.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cpi.ingestion.run-on-startup", havingValue = "true", matchIfMissing = true)
public class IngestionRunner implements CommandLineRunner {

    /** Retrieval probe questions — deliberately worded to avoid the obvious keywords. */
    private static final List<String> SAMPLE_QUERIES = List.of(
            "How do I connect to a database from an iFlow?",
            "AS2",
            "What is the capital of France?");

    private final IngestionPipeline pipeline;
    private final IngestionProperties properties;
    private final RetrievalService retrievalService;
    private final EmbeddingModel embeddingModel;

    public IngestionRunner(IngestionPipeline pipeline,
                           IngestionProperties properties,
                           RetrievalService retrievalService,
                           EmbeddingModel embeddingModel) {
        this.pipeline = pipeline;
        this.properties = properties;
        this.retrievalService = retrievalService;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        List<Document> documents = pipeline.loadDocuments();
        log.info("=== Loaded {} documents ===", documents.size());
        documents.forEach(doc -> log.info("  {}", format("%-45s %6d chars", doc.metadata().getString("file_name"), doc.text().length())));

        List<TextSegment> segments;
        try {
            segments = pipeline.split(documents);
        } catch (UnsupportedOperationException e) {
            log.error("=== Chunking not implemented yet: {} ===", e.getMessage());
            return;
        }

        log.info("=== Chunking: maxSegmentSize={} maxOverlapSize={} ===",
                properties.maxSegmentSize(), properties.maxOverlapSize());

        var sizes = segments.stream().map(s -> s.text().length()).toList();
        log.info("=== Split into {} segments ===", segments.size());
        log.info("  min {} / avg {} / max {} chars",
                sizes.stream().min(Comparator.naturalOrder()).orElse(0),
                format("%.0f", sizes.stream().mapToInt(Integer::intValue).average().orElse(0)),
                sizes.stream().max(Comparator.naturalOrder()).orElse(0));

        int sample = segments.size() / 2;
        log.info("--- Sample segment (#{}) ---{}{}{}------------------------", sample, System.lineSeparator(), segments.get(sample).text(), System.lineSeparator());

        embedAndSearch(segments);
    }

    /** Embeds every segment, then probe the store with a few questions. */
    private void embedAndSearch(List<TextSegment> segments) {
        List<Embedding> embeddings;
        long start = System.currentTimeMillis();
        try {
            embeddings = pipeline.embed(segments);
        } catch (UnsupportedOperationException e) {
            log.warn("=== Embedding not implemented yet: {} ===", e.getMessage());
            return;
        }
        log.info("=== Embedded {} segments in {} ms, {} dimensions each (model reports {}) ===",
                embeddings.size(), System.currentTimeMillis() - start,
                embeddings.isEmpty() ? 0 : embeddings.getFirst().vector().length,
                embeddingModel.dimension());

        for (String query : SAMPLE_QUERIES) {
            try {
                log.info("--- Query: \"{}\"", query);
                var matches = retrievalService.search(query, 3);
                if (matches.isEmpty()) {
                    log.info("    (no matches above the score floor)");
                }
                matches.forEach(m -> log.info("    {}",
                        format("%.4f  [%s]  %s", m.score(),
                                m.embedded().metadata().getString("file_name"),
                                preview(m.embedded().text()))));
            } catch (UnsupportedOperationException e) {
                log.warn("=== Search not implemented yet: {} ===", e.getMessage());
                return;
            }
        }
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 90 ? flat : flat.substring(0, 90) + "...";
    }
}
