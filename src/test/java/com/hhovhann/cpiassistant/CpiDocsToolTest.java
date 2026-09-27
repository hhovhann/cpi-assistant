package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** readPage: only catalog pages, in parts. */
class CpiDocsToolTest {

    private static final String KAFKA = "docs/ISuite_Integrations_APIs/configure-the-kafka-receiver-adapter-fc6ee1f.md";

    private final SapHelpCatalog catalog = new SapHelpCatalog(
            List.of(new SapHelpCatalog.Page(KAFKA, "Configure the Kafka Receiver Adapter")), null, "", "");

    private final SapHelpClient client = new SapHelpClient("http://unused") {
        @Override
        public Optional<String> fetch(String path) {
            return path.equals(KAFKA) ? Optional.of("A".repeat(CpiDocsTool.PART) + "Topic | The Kafka topic") : Optional.empty();
        }
    };

    private final CpiDocsTool docs = new CpiDocsTool(null, catalog, client);

    @Test
    void aPageIsReadInPartsUnderItsTitle() {
        String second = docs.readPage("configure the kafka receiver adapter", 2);

        assertThat(second).startsWith("[Configure the Kafka Receiver Adapter]\n(part 2 of 2)\n")
                .endsWith("Topic | The Kafka topic");
        assertThat(docs.readPage("Configure the Kafka Receiver Adapter", 9)).contains("(part 2 of 2)");
        // The model copies the title as it cites it, brackets included.
        assertThat(docs.readPage("[Configure the Kafka Receiver Adapter]", 1)).startsWith("[Configure the Kafka Receiver Adapter]\n(part 1 of 2)");
    }

    @Test
    void onlyCatalogPagesCanBeRead() {
        assertThat(docs.readPage("https://evil.example.com/page", 1)).startsWith("No page titled");
    }
}
