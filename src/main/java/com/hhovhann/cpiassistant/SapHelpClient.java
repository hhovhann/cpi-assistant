package com.hhovhann.cpiassistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads one page of the SAP Integration Suite documentation as Markdown
 * from GitHub ({@code cpi.knowledge.sap-help-url}), and strips what is only there
 * for the help portal: HTML comments, anchor tags, images — and links, which
 * keep their text. HTML tables become one line per row. A link like [Handle Errors in Successful Responses](….md)
 * looks exactly like a citation; the model copied such links as sources it was
 * never given.
 */
@Component
public class SapHelpClient {

    private final RestClient restClient;

    public SapHelpClient(@Value("${cpi.knowledge.sap-help-url}") String baseUrl) {
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

    private static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<t[dh][^>]*>(.*?)</t[dh]>");

    static String clean(String markdown) {
        String text = markdown
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("<a name=\"[^\"]*\"\\s*/>", "")
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        return tablesToRows(text)
                // Markdown escapes: \( \) \* … — the model and the embedding want the plain character.
                .replaceAll("\\\\([()\\[\\]*_#`>|])", "$1")
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    /**
     * SAP writes parameter tables in HTML. As chunks they are tag soup —
     * {@code </td> </tr> <tr> <td valign="top"> *Connection Timeout*} — hard to
     * match and hard to read. Each row becomes one line: {@code cell | cell}.
     */
    static String tablesToRows(String text) {
        Matcher rows = ROW.matcher(text);
        StringBuilder out = new StringBuilder();
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(rows.group(1));
            while (cell.find()) {
                cells.add(cell.group(1).replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip());
            }
            rows.appendReplacement(out, Matcher.quoteReplacement("\n" + String.join(" | ", cells) + "\n"));
        }
        rows.appendTail(out);
        return out.toString().replaceAll("</?(table|thead|tbody|colgroup|col)[^>]*>", "");
    }
}
