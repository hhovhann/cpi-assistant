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

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/ask")
    public RagService.RagAnswer ask(@RequestParam String question) {
        return ragService.answer(question);
    }
}
