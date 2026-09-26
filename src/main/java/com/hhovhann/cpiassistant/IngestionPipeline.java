package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a page into searchable chunks: split, embed, store.
 * Used by {@link KnowledgeService} for every SAP Help page it saves.
 */
@Service
public class IngestionPipeline {

    private final IngestionProperties properties;
    private final EmbeddingModel embeddingModel;
    private final String documentPrefix;

    public IngestionPipeline(IngestionProperties properties,
                             EmbeddingModel embeddingModel,
                             @Value("${langchain4j.open-ai.embedding-model.document-prefix:}") String documentPrefix) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.documentPrefix = documentPrefix;
    }

    /**
     * Splits documents into chunks; each chunk keeps its document's metadata
     * (page title, URL, fetch time).
     *
     * recursive() splits on paragraphs first, then sentences, then words —
     * falling back to a smaller unit only when a piece doesn't fit. Sizes come
     * from cpi.ingestion.*; 500 / 50 was the best trade-off measured on the
     * earlier hand-written docs (Step 10).
     */
    public List<TextSegment> split(List<Document> documents) {
        DocumentSplitter documentSplitter = DocumentSplitters.recursive(properties.maxSegmentSize(), properties.maxOverlapSize());
        return documentSplitter.splitAll(documents);
    }

    /**
     * Embeds the chunks and stores each vector next to the chunk it came from.
     * embedAll sends the whole batch in one HTTP call; addAll pairs the two
     * lists positionally — you search by vector, but get back text.
     *
     * What gets embedded is the document prefix, the page title and the chunk.
     * The title matters for chunks that never name their subject: a table row
     * "Connection Timeout | Provide a connection timeout …" says nothing about
     * JDBC, and "configure a JDBC adapter" never found it. The store keeps the
     * original chunk, so neither prefix nor title leaks into what the model sees.
     */
    public List<Embedding> embedInto(List<TextSegment> segments, EmbeddingStore<TextSegment> store) {
        List<TextSegment> prefixed = segments.stream()
                .map(segment -> TextSegment.from(documentPrefix + titleLine(segment) + segment.text(), segment.metadata()))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(prefixed).content();
        store.addAll(embeddings, segments);
        return embeddings;
    }

    private static String titleLine(TextSegment segment) {
        String title = segment.metadata().getString(KnowledgeService.TITLE);
        return title == null ? "" : title + "\n\n";
    }
}
