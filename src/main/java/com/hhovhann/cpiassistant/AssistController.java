package com.hhovhann.cpiassistant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The assistant's whole API: ask a question, and check that it is ready.
 */
@RestController
public class AssistController {

    /** Long enough for a question with an error text pasted in; every character goes to the model. */
    static final int MAX_QUESTION_LENGTH = 1000;

    private final AssistService assistService;
    private final SeedRunner seedRunner;

    public AssistController(AssistService assistService, SeedRunner seedRunner) {
        this.assistService = assistService;
        this.seedRunner = seedRunner;
    }

    @GetMapping("/assist")
    public AssistService.AssistAnswer assist(@RequestParam String question) {
        if (question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A question must be 1 to " + MAX_QUESTION_LENGTH + " characters.");
        }
        return assistService.assist(question.strip());
    }

    /** Ready once the popular pages are saved and the catalog titles embedded. */
    @GetMapping("/status")
    public Status status() {
        return new Status(seedRunner.isReady(), seedRunner.savedPages(), seedRunner.seedPages());
    }

    public record Status(boolean ready, int savedPages, int seedPages) {
    }
}
