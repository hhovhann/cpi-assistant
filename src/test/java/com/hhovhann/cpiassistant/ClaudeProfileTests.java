package com.hhovhann.cpiassistant;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The `claude` profile swaps the chat model on both tracks. No API call is
 * made — building the clients is offline — so this runs without a key or
 * credit. The placeholder key only satisfies ${ANTHROPIC_API_KEY}.
 */
@ActiveProfiles("claude")
@SpringBootTest(properties = {
        "cpi.ingestion.run-on-startup=false",
        "ANTHROPIC_API_KEY=test-key-not-used"})
class ClaudeProfileTests {

    @Autowired
    ChatModel langChain4jChatModel;

    @Autowired
    org.springframework.ai.chat.model.ChatModel springAiChatModel;

    @Test
    void langChain4jTrackUsesClaudeWithoutSamplingParameters() {
        assertThat(langChain4jChatModel).isInstanceOf(AnthropicChatModel.class);
        var parameters = langChain4jChatModel.defaultRequestParameters();
        assertThat(parameters.modelName()).isEqualTo("claude-opus-5-5");
        // Opus 5.5 answers temperature / top_p / top_k with a 400.
        assertThat(parameters.temperature()).isNull();
        assertThat(parameters.topP()).isNull();
        assertThat(parameters.topK()).isNull();
    }

    @Test
    void springAiTrackUsesClaudeWithoutSamplingParameters() {
        assertThat(springAiChatModel).isInstanceOf(org.springframework.ai.anthropic.AnthropicChatModel.class);
        var options = springAiChatModel.getOptions();
        assertThat(options.getModel()).isEqualTo("claude-opus-5-5");
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
    }
}
