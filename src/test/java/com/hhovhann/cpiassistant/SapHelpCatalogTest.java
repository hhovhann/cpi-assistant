package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking rules, with the real titles, summaries and scores measured for
 * these questions (nomic-embed-text, title + summary).
 */
class SapHelpCatalogTest {

    private static SapHelpCatalog.PageMatch match(String title, String summary, double score) {
        return new SapHelpCatalog.PageMatch(new SapHelpCatalog.Page("docs/x/" + title.hashCode() + "-1234567.md", title, summary), score);
    }

    private static List<String> titles(List<SapHelpCatalog.PageMatch> ranked) {
        return ranked.stream().map(m -> m.page().title()).toList();
    }

    @Test
    void anIdentifierMustMatchExactlyAs4IsNotAs2() {
        var nearest = List.of(
                match("Configure the AS2 Receiver Adapter", "The AS2 receiver adapter sends messages using AS2.", 0.902),
                match("Configure the OData V4 Receiver Adapter", "", 0.894),
                match("AS4 Receiver Adapter", "Provides basic insights on how the AS4 messaging protocol enables message exchange.", 0.886),
                match("Configure Receiver Channel with ebMS3 Pull", "Configure the AS4 receiver channel as a receiving MSH.", 0.866));

        var ranked = SapHelpCatalog.rank("How do I configure the AS4 receiver adapter?", nearest);

        // AS2 and OData are gone; of the AS4 pages the Configure one is within 0.025 of the best.
        assertThat(titles(ranked)).containsExactly("Configure Receiver Channel with ebMS3 Pull", "AS4 Receiver Adapter");
    }

    @Test
    void aConfigurePageThatMatchesClearlyWorseDoesNotWin() {
        var nearest = List.of(
                match("JDBC Receiver Adapter", "The JDBC adapter enables you to connect SAP Integration Suite to databases.", 0.909),
                match("Configure JDBC Drivers", "Upload and deploy JDBC drivers.", 0.876));

        assertThat(titles(SapHelpCatalog.rank("How do I configure a JDBC adapter?", nearest)))
                .containsExactly("JDBC Receiver Adapter", "Configure JDBC Drivers");
    }

    @Test
    void anIdentifierNoPageHasChangesNothing() {
        var nearest = List.of(match("Configure the JMS Receiver Adapter", "", 0.805), match("Apply the Retry Pattern", "", 0.802));

        assertThat(SapHelpCatalog.rank("How do I tune JVM garbage collection?", nearest)).isEqualTo(nearest);
    }

    @Test
    void identifiersAreWordsWithADigitOrTwoCapitals() {
        assertThat(SapHelpCatalog.identifiers("Configure the AS4 and OData V2 adapters for JDBC, SFTP in an iFlow"))
                .containsExactlyInAnyOrder("as4", "odata", "v2", "jdbc", "sftp");
    }
}
