package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

import static java.lang.String.format;

/**
 * Prints loading + chunking stats on startup so we can see the effect of
 * different splitting strategies without writing tests yet.
 */
@Slf4j
@Component
public class IngestionRunner implements CommandLineRunner {

    private final IngestionPipeline pipeline;

    public IngestionRunner(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
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

        var sizes = segments.stream().map(s -> s.text().length()).toList();
        log.info("=== Split into {} segments ===", segments.size());
        log.info("  min {} / avg {} / max {} chars",
                sizes.stream().min(Comparator.naturalOrder()).orElse(0),
                format("%.0f", sizes.stream().mapToInt(Integer::intValue).average().orElse(0)),
                sizes.stream().max(Comparator.naturalOrder()).orElse(0));

        int sample = segments.size() / 2;
        log.info("--- Sample segment (#{}) ---{}{}{}------------------------", sample, System.lineSeparator(), segments.get(sample).text(), System.lineSeparator());
    }
}
