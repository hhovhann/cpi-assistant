package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.IntegrationRuntimeArtifact;
import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fake tenant's contents: five iFlows and a day and a half of message
 * logs, with failures planted on purpose. Timestamps are relative to the
 * clock, so "failed today" always finds something.
 * <p>
 * Each planted error is a real CPI error text, and each has its fix in the
 * docs — so a question like "why did Order_Sync fail and how do I fix it?"
 * needs both kinds of tool: the tenant for the error, the docs for the fix.
 */
final class FakeCpiData {

    private static final String JDBC_POOL_TIMEOUT = """
            com.sap.it.rt.adapter.jdbc.exceptions.JdbcAdapterException: Failed to execute SQL statement, \
            caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, \
            request timed out after 30000ms. Data source: ORDER_DB (Receiver: OrderDB, JDBC adapter)""";

    private static final String HTTP_401 = """
            com.sap.it.rt.adapter.http.api.exception.HttpResponseException: An internal server error occured: \
            HTTP operation failed invoking https://s4.example.com/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner \
            with statusCode: 401 (Unauthorized). Credential name: S4_BASIC_AUTH""";

    private static final String MAPPING_ERROR = """
            com.sap.xi.mapping.camel.XiMappingException: com.sap.aii.mappingtool.tf7.IllegalInstanceException: \
            Cannot create target element /ns0:INVOIC/Header/PartnerID. Values missing in queue context. \
            Target XSD requires a value for this element. (Message mapping: MM_Invoice_To_EDIFACT_D96A)""";

    private static final String SFTP_REFUSED = """
            com.sap.it.rt.adapter.sftp.SftpException: Connection refused: connect to sftp.bank.example.com:22. \
            Check the host, the port and that the Cloud Connector / firewall allows the connection.""";

    private final List<MessageProcessingLog> logs = new ArrayList<>();
    private final Map<String, String> errors = new java.util.HashMap<>();
    private final List<IntegrationRuntimeArtifact> artifacts;

    FakeCpiData(Clock clock) {
        Instant now = clock.instant();

        artifacts = List.of(
                artifact("Order_Sync", "1.0.7", now.minus(Duration.ofDays(6)), "STARTED"),
                artifact("Customer_Replicate", "2.1.0", now.minus(Duration.ofDays(20)), "STARTED"),
                artifact("Invoice_To_Partner_EDI", "1.3.2", now.minus(Duration.ofDays(2)), "STARTED"),
                artifact("Payment_Status_Poll", "1.0.0", now.minus(Duration.ofDays(40)), "STARTED"),
                artifact("Material_Master_Load", "0.9.1", now.minus(Duration.ofHours(4)), "ERROR"));

        // Order_Sync: 3 JDBC pool timeouts today, among successes.
        for (int hoursAgo : new int[]{1, 3, 4, 6, 8, 10, 12, 15, 20, 30}) {
            log("Order_Sync", "COMPLETED", now, hoursAgo, "S4HANA", "OrderDB", null);
        }
        log("Order_Sync", "FAILED", now, 2, "S4HANA", "OrderDB", JDBC_POOL_TIMEOUT);
        log("Order_Sync", "FAILED", now, 5, "S4HANA", "OrderDB", JDBC_POOL_TIMEOUT);
        log("Order_Sync", "FAILED", now, 9, "S4HANA", "OrderDB", JDBC_POOL_TIMEOUT);

        log("Customer_Replicate", "COMPLETED", now, 7, "CRM", "S4HANA", null);
        log("Customer_Replicate", "FAILED", now, 3, "CRM", "S4HANA", HTTP_401);

        log("Invoice_To_Partner_EDI", "COMPLETED", now, 14, "S4HANA", "PARTNER_ACME", null);
        log("Invoice_To_Partner_EDI", "FAILED", now, 1, "S4HANA", "PARTNER_ACME", MAPPING_ERROR);
        log("Invoice_To_Partner_EDI", "FAILED", now, 26, "S4HANA", "PARTNER_ACME", MAPPING_ERROR);

        // RETRY, not FAILED: CPI is still trying. Only FAILED counts as failed.
        log("Payment_Status_Poll", "RETRY", now, 1, "Timer", "BankSFTP", SFTP_REFUSED);
        log("Payment_Status_Poll", "COMPLETED", now, 25, "Timer", "BankSFTP", null);
    }

    private static IntegrationRuntimeArtifact artifact(String name, String version, Instant deployedOn, String status) {
        return new IntegrationRuntimeArtifact(name, version, name, "INTEGRATION_FLOW", "hayk.developer",
                CpiODataModel.toODataDate(deployedOn), status);
    }

    private void log(String iflow, String status, Instant now, int hoursAgo, String sender, String receiver, String error) {
        Instant end = now.minus(Duration.ofHours(hoursAgo));
        // Stable ids, so the same seed gives the same guids on every start.
        String guid = UUID.nameUUIDFromBytes((iflow + status + hoursAgo).getBytes()).toString().replace("-", "");
        logs.add(new MessageProcessingLog(guid, "corr-" + guid.substring(0, 8), iflow, status,
                CpiODataModel.toODataDate(end.minusMillis(850)), CpiODataModel.toODataDate(end), sender, receiver));
        if (error != null) {
            errors.put(guid, error);
        }
    }

    List<MessageProcessingLog> logs() {
        return logs;
    }

    List<IntegrationRuntimeArtifact> artifacts() {
        return artifacts;
    }

    String error(String messageGuid) {
        return errors.get(messageGuid);
    }
}
