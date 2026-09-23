package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestionPipeline {

    private final IngestionProperties properties;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String documentPrefix;

    /** Written by the startup thread, read by request threads — hence atomic. */
    private final AtomicInteger indexedSegments = new AtomicInteger();

    public IngestionPipeline(IngestionProperties properties,
                             EmbeddingModel embeddingModel,
                             EmbeddingStore<TextSegment> embeddingStore,
                             @Value("${langchain4j.open-ai.embedding-model.document-prefix:}") String documentPrefix) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentPrefix = documentPrefix;
    }

    /**
     * Loads all CPI docs from src/main/resources/cpi-docs.
     * Each Document carries metadata (e.g. "file_name"), which travels with
     * every segment and identifies the source of a retrieved chunk.
     */
    public List<Document> loadDocuments() {
        return ClassPathDocumentLoader.loadDocuments("cpi-docs", new TextDocumentParser());
    }

    /**
     * Splits documents into segments for embedding.
     *
     * recursive() splits on paragraphs first, then sentences, then words —
     * falling back to a smaller unit only when a piece doesn't fit.
     *
     * Sizes come from cpi.ingestion.* so they can be tuned without a source
     * change. Default is (500, 50) → 207 segments. At (200, 0): 534 segments,
     * min 7 / avg 134 / max 200 chars.
     *
     * Why not (200, 0): min 7 means at least one segment is a bare heading.
     * One vector per segment regardless of length, so a 7-char segment embeds
     * a generic word and matches unrelated queries.
     */
    public List<TextSegment> split(List<Document> documents) {
        DocumentSplitter documentSplitter = DocumentSplitters.recursive(
                properties.maxSegmentSize(), properties.maxOverlapSize());

        return documentSplitter.splitAll(documents);
    }

    /**
     * Embeds the segments and stores each vector next to the segment it came
     * from. embedAll sends the whole batch in one HTTP call; addAll pairs the
     * two lists positionally — you search by vector, but get back text.
     *
     * The document prefix is applied to the text that gets embedded only.
     * The store keeps the original segment, so the prefix never leaks into
     * what retrieval hands to the LLM.
     */
    public List<Embedding> embed(List<TextSegment> segments) {
        List<TextSegment> prefixed = segments.stream()
                .map(segment -> TextSegment.from(documentPrefix + segment.text(), segment.metadata()))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(prefixed).content();

        embeddingStore.addAll(embeddings, segments);
        indexedSegments.addAndGet(embeddings.size());

        return embeddings;
    }

    /** How many segments are searchable. Zero until startup ingestion finishes. */
    public int indexedSegments() {
        return indexedSegments.get();
    }
}
