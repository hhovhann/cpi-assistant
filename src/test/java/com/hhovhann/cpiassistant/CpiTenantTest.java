package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant tools end to end, over real HTTP: CpiTenantClient calls the fake
 * tenant served by this app, exactly as it would call a real one. No LLM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "cpi.tenant.fake=true")
class CpiTenantTest {

    @LocalServerPort
    int port;

    CpiTenantClient client;
    CpiTenantTools tools;

    @BeforeEach
    void pointAtTheFakeTenant() {
        client = new CpiTenantClient("http://localhost:" + port + "/fake-cpi/api/v1");
        tools = new CpiTenantTools(client, null);
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    @Test
    void failedMessagesForOneIflowNewestFirst() {
        var failed = client.messages("FAILED", "Order_Sync", hoursAgo(24), 20);

        assertThat(failed).hasSize(3)
                .allSatisfy(log -> assertThat(log.status()).isEqualTo("FAILED"))
                .extracting(MessageProcessingLog::integrationFlowName).containsOnly("Order_Sync");
        assertThat(failed).extracting(log -> CpiODataModel.fromODataDate(log.logEnd()))
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void theTimeWindowAndRetriesAreRespected() {
        // Last 24 h: 3 Order_Sync, 1 Customer_Replicate, 1 Invoice (the other is 26 h old), 1 Partner_Webhook.
        // Payment_Status_Poll is RETRY, not FAILED, so it is not in these.
        assertThat(client.messages("FAILED", null, hoursAgo(24), 20)).hasSize(6);
        assertThat(client.messages("FAILED", null, hoursAgo(48), 20)).hasSize(7);
        assertThat(client.messages("FAILED", null, hoursAgo(48), 2)).hasSize(2);
    }

    @Test
    void aQuoteInTheIflowNameIsEscapedNotInjected() {
        assertThat(client.messages("FAILED", "Order_Sync' or Status eq 'COMPLETED", hoursAgo(24), 20)).isEmpty();
    }

    @Test
    void errorInformationForAFailedMessageAndNothingForAnUnknownId() {
        String guid = client.messages("FAILED", "Order_Sync", hoursAgo(24), 1).getFirst().messageGuid();

        assertThat(client.errorInformation(guid)).hasValueSatisfying(error -> assertThat(error)
                .contains("JdbcAdapterException", "Connection is not available"));
        assertThat(client.errorInformation("does-not-exist")).isEmpty();
    }

    @Test
    void runtimeArtifactsIncludeOneInError() {
        assertThat(client.runtimeArtifacts()).hasSize(6)
                .anySatisfy(a -> {
                    assertThat(a.name()).isEqualTo("Material_Master_Load");
                    assertThat(a.status()).isEqualTo("ERROR");
                });
    }

    @Test
    void retriesAreProblemsToo() {
        // The blind spot this tool used to have: RETRY was invisible.
        String payment = tools.getProblemMessages("Payment_Status_Poll", null, null);
        assertThat(payment).startsWith("1 message(s) with problems for Payment_Status_Poll in the last 24 hours (1 RETRY):")
                .contains("| RETRY (still retrying, not failed) | Payment_Status_Poll |");

        String id = payment.substring(payment.indexOf("message id ") + 11).split(" ")[0];
        assertThat(tools.getErrorDetails(id)).contains("SftpException", "Connection refused");

        // All problems in the last 24 h: 6 FAILED plus the RETRY.
        assertThat(tools.getProblemMessages(null, null, null)).startsWith("7 message(s) with problems for any iFlow in the last 24 hours (6 FAILED, 1 RETRY):");
    }

    @Test
    void toolsAnswerInLinesTheModelCanRead() {
        assertThat(tools.listIflows()).contains("Order_Sync | version 1.0.7 | STARTED | deployed ");

        String orderSync = tools.getProblemMessages("Order_Sync", null, null);
        assertThat(orderSync).startsWith("3 message(s) with problems for Order_Sync in the last 24 hours (3 FAILED):")
                .contains("| FAILED | Order_Sync | message id ", "S4HANA -> OrderDB");

        assertThat(tools.getProblemMessages("Order_Sync", "RETRY", null))
                .isEqualTo("No messages in status RETRY for Order_Sync in the last 24 hours.");
        assertThat(tools.getProblemMessages(null, "COMPLETED", null)).startsWith("Unknown status COMPLETED.");
        // The model sends lists like this; it used to take three tries.
        assertThat(tools.getProblemMessages("Payment_Status_Poll", "FAILED,RETRY, ESCALATED", null))
                .startsWith("1 message(s) with problems for Payment_Status_Poll in the last 24 hours (1 RETRY):");
        assertThat(tools.getErrorDetails("nope")).isEqualTo("No error information for message nope. Check the id.");
    }

    @Test
    void aRetryMessageIsSpelledOutAsNotFailed() {
        String payment = tools.getProblemMessages("Payment_Status_Poll", null, null);

        assertThat(payment).contains("| RETRY (still retrying, not failed) | Payment_Status_Poll |")
                .endsWith("A RETRY message has not failed: CPI is still retrying it. Report it as retrying.");
        assertThat(tools.getProblemMessages("Order_Sync", null, null)).doesNotContain("RETRY");
    }

    @Test
    void anEmptyResultSaysWhatTheModelShouldCheckNext() {
        assertThat(tools.listIflows()).endsWith("This is deployment status only. Whether messages are failing or retrying: call getProblemMessages.");
        // A typo: not deployed, and the closest name.
        assertThat(tools.getProblemMessages("Order_Synk", null, null))
                .startsWith("No messages in status FAILED, RETRY, ESCALATED for Order_Synk")
                .contains("No iFlow named Order_Synk is deployed. Did you mean Order_Sync?");
        // A name nothing is close to: no guess.
        assertThat(tools.getProblemMessages("Unknown_Flow", null, null))
                .contains("No iFlow named Unknown_Flow is deployed.").doesNotContain("Did you mean");
        // The whole tenant: an iFlow in ERROR is a problem too.
        assertThat(tools.getProblemMessages(null, null, null)).endsWith("Not running (deployment): Material_Master_Load (ERROR).");
        assertThat(CpiTenantTools.distance("order_synk", "order_sync")).isEqualTo(1);
    }

    @Test
    void anErrorComesWithTheDocumentationForIt() {
        List<String> queries = new java.util.ArrayList<>();
        var knowledge = new KnowledgeService(null, null, null, null, null, 0.82, java.time.Duration.ofDays(30), null) {
            @Override
            public Found find(String query) {
                queries.add(query);
                return new Found(List.of(CpiAgentTest.FakeKnowledge.jdbcMatch()), List.of(), List.of());
            }
        };
        var withDocs = new CpiTenantTools(client, knowledge);
        String id = tools.getProblemMessages("Order_Sync", "FAILED", null).lines().skip(1).findFirst().orElseThrow()
                .replaceAll(".*message id (\\w+).*", "$1");

        String details = withDocs.getErrorDetails(id);

        assertThat(details).contains("HikariPool-1", "SAP documentation about this error", "[JDBC Receiver Adapter]\n");
        // Package names are dropped: the search gets the exception and the message.
        assertThat(queries).singleElement().asString()
                .startsWith("JdbcAdapterException: Failed to execute SQL statement, caused by: SQLTransientConnectionException:");
    }

    @Test
    void aRealTenantGetsABearerTokenFromTheTokenUrlAndReusesIt() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            calls.incrementAndGet();
            boolean basic = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            byte[] body = (basic ? "{\"access_token\":\"t-1\",\"expires_in\":3600}" : "{}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(basic ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/IntegrationRuntimeArtifacts", exchange -> {
            boolean bearer = "Bearer t-1".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"d\":{\"results\":[]}}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(bearer ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            var real = new CpiTenantClient(base + "/api/v1", base + "/oauth/token", "client", "secret");

            assertThat(real.isConfigured()).isTrue();
            assertThat(real.runtimeArtifacts()).isEmpty();
            assertThat(real.runtimeArtifacts()).isEmpty();
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
