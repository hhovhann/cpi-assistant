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

@Service
public class IngestionPipeline {

    private final IngestionProperties properties;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String documentPrefix;

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
     * Each Document carries metadata (e.g. "file_name") — we'll use that
     * in Step 8 to show source references in answers.
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
     * Sizes come from cpi.ingestion.* so Step 10 can sweep them without a
     * source change. At the default (200, 0): 534 segments,
     * min 7 / avg 134 / max 200 chars.
     *
     * Observation: min 7 means at least one segment is a bare heading. One
     * vector per segment regardless of length, so a 7-char segment embeds a
     * generic word and matches unrelated queries. Revisit in Step 10 —
     * compare against (500, 50), which should raise the floor.
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
     * what retrieval hands to the LLM in Step 6.
     */
    public List<Embedding> embed(List<TextSegment> segments) {
        List<TextSegment> prefixed = segments.stream()
                .map(segment -> TextSegment.from(documentPrefix + segment.text(), segment.metadata()))
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(prefixed).content();

        embeddingStore.addAll(embeddings, segments);

        return embeddings;
    }
}
