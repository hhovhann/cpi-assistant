package com.hhovhann.cpiassistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Downloads one page of the SAP Integration Suite documentation as Markdown
 * from GitHub ({@code cpi.sap-help.base-url}), and strips what is only there
 * for the help portal: HTML comments and anchor tags.
 */
@Component
public class SapHelpClient {

    private final RestClient restClient;

    public SapHelpClient(@Value("${cpi.sap-help.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** The page text; empty if the page no longer exists at that path. */
    public Optional<String> fetch(String path) {
        try {
            // Appended as a path, not a {variable}: a variable would encode the slashes.
            String markdown = restClient.get().uri(uri -> uri.path("/" + path).build()).retrieve().body(String.class);
            return Optional.ofNullable(markdown).map(SapHelpClient::clean).filter(text -> !text.isBlank());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    static String clean(String markdown) {
        return markdown
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("<a name=\"[^\"]*\"\\s*/>", "")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
