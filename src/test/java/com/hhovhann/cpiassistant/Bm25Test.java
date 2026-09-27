package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The keyword half of hybrid search, and how the two halves are fused. */
class Bm25Test {

    @Test
    void theRareWordDecides() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("sftp", "Configure the SFTP Receiver Adapter. Address, proxy, authentication for the SFTP server.");
        pages.put("hosts", "Maintaining SSH Known Hosts for SFTP Connectivity. Upload the known hosts file.");
        pages.put("jdbc", "JDBC Receiver Adapter. Connect to a database.");

        var ranked = new Bm25(pages).rank("How do I set up an SFTP receiver with known hosts?", 3);

        // "receiver" is on the JDBC page too, but "known" and "hosts" are rare and decide.
        assertThat(ranked).extracting(Bm25.Scored::id).startsWith("hosts").endsWith("jdbc");
    }

    @Test
    void stopWordsAreNotSearchedFor() {
        assertThat(Bm25.terms("How do I configure a JDBC adapter?")).containsExactly("configure", "jdbc", "adapter");
    }

    @Test
    void fusionRewardsAgreementBetweenTheLists() {
        // "b" is second by meaning and first by keywords: it wins over "a", first by meaning only.
        assertThat(Bm25.fuse(List.of(List.of("a", "b", "c"), List.of("b", "c")))).containsExactly("b", "c", "a");
    }

    @Test
    void keywordsLiftAChunkBothListsLikeButAddNone() {
        var general = match("general", "Configure the SFTP Receiver Adapter", "Address and proxy settings.", 0.90);
        var other = match("other", "HTTP Receiver Adapter", "Set the HTTP address.", 0.88);
        var hosts = match("hosts", "Configure the SFTP Receiver Adapter", "Maintain the known hosts file on the tenant.", 0.86);

        var ordered = RetrievalService.hybridOrder("SFTP known hosts", List.of(general, other, hosts));

        // Third by meaning, first by keywords: it passes "other", which only meaning liked.
        // It does not pass "general", which both lists rank high — rank fusion rewards agreement.
        assertThat(ordered).extracting(EmbeddingMatch::embeddingId).containsExactly("general", "hosts", "other");
        // Scores stay similarities, so the floor and the "best" in the path keep their meaning.
        assertThat(ordered.get(1).score()).isEqualTo(0.86);
    }

    private static EmbeddingMatch<TextSegment> match(String id, String title, String text, double score) {
        return new EmbeddingMatch<>(score, id, null, TextSegment.from(text, Metadata.from(KnowledgeService.TITLE, title)));
    }
}
