package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.IntegrationRuntimeArtifact;
import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import com.hhovhann.cpiassistant.CpiODataModel.ODataList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a CPI tenant through its OData API — the real one, or the fake in
 * this app ({@link FakeCpiController}); {@code cpi.tenant.base-url} decides.
 * Read-only on purpose: the model can look, never change anything.
 * <p>
 * Not yet here for a real tenant: OAuth. A real tenant wants a bearer token
 * from the service key's token URL (client credentials) on every call.
 */
@Component
public class CpiTenantClient {

    private static final ParameterizedTypeReference<ODataList<MessageProcessingLog>> LOGS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ODataList<IntegrationRuntimeArtifact>> ARTIFACTS = new ParameterizedTypeReference<>() {
    };
    /** OData V2 datetime literal: UTC, no zone suffix. */
    private static final DateTimeFormatter ODATA_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    public CpiTenantClient(@Value("${cpi.tenant.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * FAILED messages that ended after {@code since}, newest first.
     *
     * @param iflowName exact iFlow name, or null for every iFlow
     */
    public List<MessageProcessingLog> failedMessages(String iflowName, Instant since, int top) {
        List<String> conditions = new ArrayList<>();
        conditions.add("Status eq 'FAILED'");
        if (iflowName != null && !iflowName.isBlank()) {
            // OData escapes a quote inside a string literal by doubling it.
            conditions.add("IntegrationFlowName eq '" + iflowName.replace("'", "''") + "'");
        }
        conditions.add("LogEnd gt datetime'" + ODATA_DATETIME.format(since.truncatedTo(ChronoUnit.SECONDS)) + "'");
        String filter = String.join(" and ", conditions);

        ODataList<MessageProcessingLog> body = restClient.get()
                .uri("/MessageProcessingLogs?$filter={filter}&$orderby={orderBy}&$top={top}&$format=json",
                        filter, "LogEnd desc", top)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LOGS);
        return body == null ? List.of() : body.results();
    }

    /** The error text CPI stored for a failed message; empty if there is no such message or no error. */
    public Optional<String> errorInformation(String messageGuid) {
        try {
            String text = restClient.get()
                    .uri("/MessageProcessingLogs('{guid}')/ErrorInformation/$value", messageGuid)
                    .accept(MediaType.TEXT_PLAIN)
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(text).filter(t -> !t.isBlank());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Every deployed iFlow with its runtime status. */
    public List<IntegrationRuntimeArtifact> runtimeArtifacts() {
        ODataList<IntegrationRuntimeArtifact> body = restClient.get()
                .uri("/IntegrationRuntimeArtifacts?$format=json")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ARTIFACTS);
        return body == null ? List.of() : body.results();
    }
}
