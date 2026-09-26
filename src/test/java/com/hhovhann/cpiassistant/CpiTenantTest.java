package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant tools end to end, over real HTTP: CpiTenantClient calls the fake
 * tenant served by this app, exactly as it would call a real one. No LLM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cpi.ingestion.run-on-startup=false")
class CpiTenantTest {

    @LocalServerPort
    int port;

    CpiTenantClient client;
    CpiTenantTools tools;

    @BeforeEach
    void pointAtTheFakeTenant() {
        client = new CpiTenantClient("http://localhost:" + port + "/fake-cpi/api/v1");
        tools = new CpiTenantTools(client);
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
        // Last 24 h: 3 Order_Sync, 1 Customer_Replicate, 1 Invoice (the other is 26 h old).
        // Payment_Status_Poll is RETRY, not FAILED, so it is not in these.
        assertThat(client.messages("FAILED", null, hoursAgo(24), 20)).hasSize(5);
        assertThat(client.messages("FAILED", null, hoursAgo(48), 20)).hasSize(6);
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
        assertThat(client.runtimeArtifacts()).hasSize(5)
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
                .contains("| RETRY | Payment_Status_Poll |");

        String id = payment.substring(payment.indexOf("message id ") + 11).split(" ")[0];
        assertThat(tools.getErrorDetails(id)).contains("SftpException", "Connection refused");

        // All problems in the last 24 h: 5 FAILED plus the RETRY.
        assertThat(tools.getProblemMessages(null, null, null)).startsWith("6 message(s) with problems for any iFlow in the last 24 hours (5 FAILED, 1 RETRY):");
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
        assertThat(tools.getErrorDetails("nope")).isEqualTo("No error information for message nope. Check the id.");
    }
}
