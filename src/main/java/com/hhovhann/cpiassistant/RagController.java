package com.hhovhann.cpiassistant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Question in, grounded answer out. /chat stays as the no-RAG
 * baseline, so the same question can be asked both ways.
 */
@RestController
public class RagController {

    private final RagService ragService;
    private final IngestionPipeline ingestionPipeline;

    public RagController(RagService ragService, IngestionPipeline ingestionPipeline) {
        this.ragService = ragService;
        this.ingestionPipeline = ingestionPipeline;
    }

    @GetMapping("/ask")
    public RagService.RagAnswer ask(@RequestParam String question) {
        return ragService.answer(question);
    }

    /**
     * The HTTP server starts before ingestion finishes. Until it does, the
     * store is empty and every question gets "I don't know" — the UI polls
     * this and waits instead.
     */
    @GetMapping("/status")
    public Status status() {
        int segments = ingestionPipeline.indexedSegments();
        return new Status(segments > 0, segments);
    }

    public record Status(boolean ready, int indexedSegments) {
    }
}
