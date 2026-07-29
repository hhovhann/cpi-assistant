package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
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
     * TODO(Hayk) — Step 4 exercise: split documents into segments.
     *
     * Starting point: DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize)
     * from dev.langchain4j.data.document.splitter.DocumentSplitters.
     * It tries to split on paragraphs first, then sentences, then words —
     * only falling back to the smaller unit when a piece doesn't fit.
     *
     * Things to experiment with (the runner prints stats for each attempt):
     * - maxSegmentSize 200 vs 500 vs 1000 (characters, unless you pass a token estimator)
     * - overlap 0 vs 50 vs 100 — what happens to instructions that span a boundary?
     * - look at an actual chunk: does "Step 3: Use the JDBC Adapter..." still make
     *   sense without the lines above it?
     */
    public List<TextSegment> split(List<Document> documents) {
        throw new UnsupportedOperationException("Step 4 exercise: implement chunking here");
    }
}