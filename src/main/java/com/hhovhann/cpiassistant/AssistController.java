package com.hhovhann.cpiassistant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant's whole API: ask a question, and check that it is ready.
 */
@RestController
public class AssistController {

    private final AssistService assistService;
    private final SeedRunner seedRunner;

    public AssistController(AssistService assistService, SeedRunner seedRunner) {
        this.assistService = assistService;
        this.seedRunner = seedRunner;
    }

    @GetMapping("/assist")
    public AssistService.AssistAnswer assist(@RequestParam String question) {
        return assistService.assist(question);
    }

    /** Ready once the popular pages are saved and the catalog titles embedded. */
    @GetMapping("/status")
    public Status status() {
        return new Status(seedRunner.isReady(), seedRunner.savedPages(), seedRunner.seedPages());
    }

    public record Status(boolean ready, int savedPages, int seedPages) {
    }
}
