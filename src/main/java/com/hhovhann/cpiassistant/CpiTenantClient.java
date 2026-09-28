package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.IntegrationRuntimeArtifact;
import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import com.hhovhann.cpiassistant.CpiODataModel.ODataList;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Reads a CPI tenant through its OData API: a real tenant, or the fake one in
 * the {@code dev} profile. {@code cpi.tenant.base-url} decides; empty means no
 * tenant, and the tenant tools are not offered to the model.
 * <p>
 * A real tenant wants OAuth: a bearer token from the service key's token URL
 * (client credentials), cached until shortly before it expires. The client id
 * and secret come from environment variables, never from a file in the repo.
 * Read-only on purpose: the model can look, never change anything.
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
    private final boolean configured;

    @Autowired
    public CpiTenantClient(@Value("${cpi.tenant.base-url:}") String baseUrl,
                           @Value("${cpi.tenant.token-url:}") String tokenUrl,
                           @Value("${cpi.tenant.client-id:}") String clientId,
                           @Value("${cpi.tenant.client-secret:}") String clientSecret) {
        this.configured = !baseUrl.isBlank();
        RestClient.Builder builder = RestClient.builder().baseUrl(configured ? baseUrl : "http://localhost");
        if (!tokenUrl.isBlank()) {
            OAuthClientCredentials oauth = new OAuthClientCredentials(tokenUrl, clientId, clientSecret);
            builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(oauth.token());
                return execution.execute(request, body);
            });
        }
        this.restClient = builder.build();
    }

    /** For tests and the fake tenant: no OAuth. */
    public CpiTenantClient(String baseUrl) {
        this(baseUrl, "", "", "");
    }

    /** A tenant URL is set: the tenant tools are offered. */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Messages in one status that ended after {@code since}, newest first.
     *
     * @param status    COMPLETED, FAILED, RETRY, ESCALATED or PROCESSING
     * @param iflowName exact iFlow name, or null for every iFlow
     */
    public List<MessageProcessingLog> messages(String status, String iflowName, Instant since, int top) {
        List<String> conditions = new ArrayList<>();
        conditions.add("Status eq '" + status.replace("'", "''") + "'");
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
