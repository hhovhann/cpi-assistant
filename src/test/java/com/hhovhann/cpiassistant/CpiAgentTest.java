package com.hhovhann.cpiassistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant's wiring without an LLM: a scripted fake model plays the
 * model's part, so this checks what the model receives.
 */
class CpiAgentTest {

    static final String JDBC = "[JDBC Receiver Adapter]\nAdd a receiver channel and select JDBC.";

    /** Answers each request with whatever the script says, given the request number. */
    static final class ScriptedChatModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final Function<Integer, AiMessage> script;

        ScriptedChatModel(Function<Integer, AiMessage> script) {
            this.script = script;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return ChatResponse.builder().aiMessage(script.apply(requests.size())).build();
        }
    }

    static CpiAgent agent(ChatModel model) {
        return new LangChain4jConfig().cpiAgent(model);
    }

    @Test
    void theQuestionAndPassagesGoToTheModelInOneCallWithNoTools() {
        var model = new ScriptedChatModel(n -> AiMessage.from("Use the JDBC adapter [JDBC Receiver Adapter]."));

        Result<String> result = agent(model).answer(JDBC, "How do I connect to a database?");

        assertThat(result.content()).isEqualTo("Use the JDBC adapter [JDBC Receiver Adapter].");
        assertThat(model.requests).singleElement().satisfies(request -> {
            assertThat(request.toolSpecifications()).isNullOrEmpty();
            assertThat(request.messages()).last()
                    .isInstanceOfSatisfying(UserMessage.class, message -> assertThat(message.singleText())
                            .contains("[JDBC Receiver Adapter]", "Question: How do I connect to a database?"));
        });
    }
}
