package com.hhovhann.cpiassistant;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code cpi.chat.provider} picks the chat model. No API call is made —
 * building the clients is offline — so this runs without keys or credit.
 */
class ChatProviderTests {

    @Nested
    @SpringBootTest
    class ByDefault {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesQwenOnLmStudioAtTemperatureZero() {
            assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
            var parameters = chatModel.defaultRequestParameters();
            assertThat(parameters.modelName()).isEqualTo("qwen/qwen3-14b");
            assertThat(parameters.temperature()).isEqualTo(0.0);
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "cpi.chat.provider=anthropic",
            "cpi.chat.anthropic.api-key=test-key-not-used"})
    class Anthropic {

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

    @Nested
    @SpringBootTest(properties = {
            "cpi.chat.provider=openai",
            "cpi.chat.openai.api-key=test-key-not-used"})
    class OpenAi {

        @Autowired
        ChatModel chatModel;

        @Test
        void usesOpenAiWithoutTemperature() {
            assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
            var parameters = chatModel.defaultRequestParameters();
            assertThat(parameters.modelName()).isEqualTo("gpt-5-mini");
            assertThat(parameters.temperature()).isNull();
        }
    }

    @Test
    void missingKeyFailsAtStartupWithTheFix() {
        var config = new LangChain4jConfig();
        for (var provider : new ChatProperties.Provider[] {ChatProperties.Provider.OPENAI, ChatProperties.Provider.ANTHROPIC}) {
            var chat = new ChatProperties(provider, Duration.ofMinutes(3), null, false, false,
                    new ChatProperties.OpenAiCompatible("http://localhost:1234/v1", "lm-studio", "llama", 0.0),
                    new ChatProperties.OpenAiCompatible("https://api.openai.com/v1", "", "gpt-5-mini", null),
                    new ChatProperties.Anthropic(null, "claude-opus-5-5", 16000));

            assertThatThrownBy(() -> config.chatModel(config.langChain4jHttpClientBuilder(), chat))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(provider == ChatProperties.Provider.OPENAI ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY");
        }
    }
}
