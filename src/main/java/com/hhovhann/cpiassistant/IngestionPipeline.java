package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionPipeline {

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
     * Current setting: 200 chars, no overlap.
     * Result: 534 segments, min 7 / avg 134 / max 200 chars.
     *
     * Observation: min 7 means at least one segment is a bare heading. One
     * vector per segment regardless of length, so a 7-char segment embeds a
     * generic word and matches unrelated queries. Revisit in Step 10 —
     * compare against (500, 50), which should raise the floor.
     */
    public List<TextSegment> split(List<Document> documents) {
        DocumentSplitter documentSplitter = DocumentSplitters.recursive(200, 0);

        return documentSplitter.splitAll(documents);
    }
}