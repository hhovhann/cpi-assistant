package com.hhovhann.cpiassistant;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the timeout wiring. OpenAiChatModel overrides the HTTP client's
 * timeouts with its own 60-second default, so a timeout set anywhere but on
 * the model silently does nothing. A local server that answers slowly shows
 * whether the configured timeout really cuts the call off.
 */
class LangChain4jConfigTest {

    private HttpServer slowServer;

    @BeforeEach
    void startSlowServer() throws IOException {
        slowServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowServer.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000);
                exchange.sendResponseHeaders(500, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        // The handler sleeps; keep it off the server's accept thread.
        slowServer.setExecutor(Executors.newCachedThreadPool());
        slowServer.start();
    }

    @AfterEach
    void stopSlowServer() {
        slowServer.stop(0);
    }

    @Test
    void chatModelTimeoutIsApplied() {
        var server = new ChatProperties.OpenAiCompatible(
                "http://localhost:" + slowServer.getAddress().getPort() + "/v1", "test-key", "test-model", null);
        var chat = new ChatProperties(ChatProperties.Provider.LMSTUDIO, Duration.ofMillis(300), 0, false, false,
                server, server, new ChatProperties.Anthropic(null, null, 16000));
        var config = new LangChain4jConfig();
        ChatModel model = config.chatModel(config.langChain4jHttpClientBuilder(), chat);

        long start = System.nanoTime();
        assertThatThrownBy(() -> model.chat("hello")).isInstanceOf(TimeoutException.class);
        // One attempt, no retries: cut off after 0.3 s, long before the server's
        // 5 s — let alone the 60 s default the bug silently fell back to, which
        // would have waited for the server and failed with a 500 instead.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
}
