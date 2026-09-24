package com.hhovhann.cpiassistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Track B — the same question, answered through Spring AI, so the two
 * frameworks can be compared side by side against the same model (LM Studio,
 * or Claude under the `claude` profile).
 */
@RestController
public class SpringAiChatController {

    private final ChatClient chatClient;

    public SpringAiChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/springai/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt().user(message).call().content();
    }
}
