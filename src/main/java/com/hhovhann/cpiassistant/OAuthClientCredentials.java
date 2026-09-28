package com.hhovhann.cpiassistant;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * An OAuth client-credentials token, as SAP BTP service keys issue them:
 * fetched from the token URL on first use, cached, and fetched again a minute
 * before it expires. Used for the CPI tenant and for official SAP MCP servers.
 * The client id and secret come from environment variables, never from a file
 * in the repository.
 */
public final class OAuthClientCredentials {

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private String value;
    private Instant expiresAt = Instant.EPOCH;

    public OAuthClientCredentials(String tokenUrl, String clientId, String clientSecret) {
        this.tokenClient = RestClient.builder().baseUrl(tokenUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** A valid access token. */
    public synchronized String token() {
        if (value == null || Instant.now().isAfter(expiresAt.minusSeconds(60))) {
            TokenResponse response = tokenClient.post()
                    .headers(h -> h.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.access_token() == null) {
                throw new IllegalStateException("The token URL returned no access token");
            }
            value = response.access_token();
            expiresAt = Instant.now().plusSeconds(response.expires_in());
        }
        return value;
    }

    record TokenResponse(String access_token, long expires_in) {
    }
}
