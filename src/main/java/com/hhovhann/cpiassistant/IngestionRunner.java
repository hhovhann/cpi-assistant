package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Prints loading + chunking stats on startup so we can see the effect of
 * different splitting strategies without writing tests yet.
 */
@Component
public class IngestionRunner implements CommandLineRunner {

    private final IngestionPipeline pipeline;

    public IngestionRunner(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void run(String... args) {
        List<Document> documents = pipeline.loadDocuments();
        System.out.printf("%n=== Loaded %d documents ===%n", documents.size());
        documents.forEach(doc -> System.out.printf("  %-45s %6d chars%n",
                doc.metadata().getString("file_name"), doc.text().length()));

        List<TextSegment> segments;
        try {
            segments = pipeline.split(documents);
        } catch (UnsupportedOperationException e) {
            System.out.printf("%n=== Chunking not implemented yet: %s ===%n%n", e.getMessage());
            return;
        }

        var sizes = segments.stream().map(s -> s.text().length()).toList();
        System.out.printf("%n=== Split into %d segments ===%n", segments.size());
        System.out.printf("  min %d / avg %.0f / max %d chars%n",
                sizes.stream().min(Comparator.naturalOrder()).orElse(0),
                sizes.stream().mapToInt(Integer::intValue).average().orElse(0),
                sizes.stream().max(Comparator.naturalOrder()).orElse(0));

        System.out.printf("%n--- Sample segment (#%d) ---%n%s%n---------------------------%n%n",
                segments.size() / 2, segments.get(segments.size() / 2).text());
    }
}