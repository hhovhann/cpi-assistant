package com.hhovhann.cpiassistant;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The `claude` profile swaps the chat model to Claude. No API call is
 * made — building the clients is offline — so this runs without a key or
 * credit. The placeholder key only satisfies ${ANTHROPIC_API_KEY}.
 */
@ActiveProfiles("claude")
@SpringBootTest(properties = {
        "cpi.ingestion.run-on-startup=false",
        "ANTHROPIC_API_KEY=test-key-not-used"})
class ClaudeProfileTests {

    @Autowired
    ChatModel chatModel;

    @Test
    void usesClaudeWithoutSamplingParameters() {
        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
        var parameters = chatModel.defaultRequestParameters();
        assertThat(parameters.modelName()).isEqualTo("claude-opus-5-5");
        // Opus 5.5 answers temperature / top_p / top_k with a 400.
        assertThat(parameters.temperature()).isNull();
        assertThat(parameters.topP()).isNull();
        assertThat(parameters.topK()).isNull();
    }
}
