package com.hhovhann.cpiassistant;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SAP Help fallback without the internet or an LLM: a local HTTP server
 * plays GitHub, and a bag-of-words fake plays the embedding model, so texts
 * that share words land close together.
 */
class SapHelpToolsTest {

    private static final String SFTP_PATH = "docs/ISuite_Integrations_APIs/configure-the-sftp-receiver-adapter-4ea5d8e.md";
    private static final String SFTP_PAGE = """
            <!-- loio4ea5d8e -->
            <a name="loio4ea5d8e"/>
            # Configure the SFTP Receiver Adapter

            The SFTP receiver adapter connects to an SFTP server. Maintain the known hosts \
            file on the tenant, and allow port 22 in the Cloud Connector.
            """;

    private HttpServer github;
    private final AtomicInteger downloads = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
    private InMemoryEmbeddingStore<TextSegment> store;
    private RetrievalService retrieval;
    private SapHelpTools tools;

    /** Each word hashed into one of 64 buckets: shared words mean similar vectors. */
    private static final class BagOfWords implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(s -> embedText(s.text())).toList());
        }

        private static Embedding embedText(String text) {
            float[] vector = new float[64];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() > 2) {
                    vector[Math.floorMod(word.hashCode(), 64)] += 1;
                }
            }
            vector[63] += 0.01f; // never all zeros
            return Embedding.from(vector);
        }

        @Override
        public int dimension() {
            return 64;
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        github = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        github.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring(1);
            byte[] body = SFTP_PAGE.getBytes(StandardCharsets.UTF_8);
            if (path.equals(SFTP_PATH)) {
                downloads.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        github.start();

        var model = new BagOfWords();
        store = new InMemoryEmbeddingStore<>();
        retrieval = new RetrievalService(model, store, "", 0.0);
        var pipeline = new IngestionPipeline(new IngestionProperties(500, 50), model, store, "");
        var catalog = new SapHelpCatalog(List.of(
                new SapHelpCatalog.Page(SFTP_PATH, "Configure the SFTP Receiver Adapter"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/define-exception-subprocess-690e078.md", "Define Exception Subprocess"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/moved-page-1234567.md", "A Page That Moved")),
                model, "", "");
        var client = new SapHelpClient("http://localhost:" + github.getAddress().getPort());
        tools = new SapHelpTools(catalog, client, pipeline, retrieval, store, Duration.ofDays(30), clock);
    }

    @AfterEach
    void tearDown() {
        github.stop(0);
    }

    private int storedChunks() {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[64])).maxResults(1000).minScore(0.0).build()).matches().size();
    }

    @Test
    void searchFindsPagesByTitle() {
        String result = tools.searchSapHelp("SFTP receiver adapter");

        assertThat(result.lines().skip(1).findFirst().orElseThrow())
                .isEqualTo("- Configure the SFTP Receiver Adapter | page id: configure-the-sftp-receiver-adapter-4ea5d8e");
    }

    @Test
    void firstReadDownloadsAndSavesTheSecondReadDoesNot() {
        String first = tools.readSapHelpPage("configure-the-sftp-receiver-adapter-4ea5d8e", "Which port must be open for SFTP?");

        assertThat(first).startsWith("From SAP Help, Configure the SFTP Receiver Adapter (downloaded now):")
                .contains("[" + SapHelpCatalog.REPO_BLOB + SFTP_PATH + "]", "allow port 22")
                .doesNotContain("<!--", "<a name");
        assertThat(downloads).hasValue(1);
        int chunks = storedChunks();
        assertThat(chunks).isPositive();

        String second = tools.readSapHelpPage("configure-the-sftp-receiver-adapter-4ea5d8e", "known hosts");

        assertThat(second).contains("(saved)");
        assertThat(downloads).hasValue(1);
        assertThat(storedChunks()).isEqualTo(chunks);
    }

    @Test
    void aSavedPageIsFoundByTheNormalDocsSearchAndCitedByUrl() {
        tools.readSapHelpPage("configure-the-sftp-receiver-adapter-4ea5d8e", "SFTP known hosts");

        var match = retrieval.search("SFTP known hosts Cloud Connector", 1).getFirst();

        assertThat(match.embedded().metadata().getString(IngestionPipeline.SOURCE)).isEqualTo(SapHelpTools.SAP_HELP);
        assertThat(RetrievalService.sourceOf(match.embedded())).isEqualTo(SapHelpCatalog.REPO_BLOB + SFTP_PATH);
        assertThat(new CpiDocsTool(retrieval).searchCpiDocs("SFTP known hosts")).startsWith("[" + SapHelpCatalog.REPO_BLOB);
    }

    @Test
    void aPageOlderThanMaxAgeIsDownloadedAgainAndReplaced() {
        tools.readSapHelpPage("configure-the-sftp-receiver-adapter-4ea5d8e", "SFTP");
        int chunks = storedChunks();

        clock.now = clock.now.plus(Duration.ofDays(31));
        String again = tools.readSapHelpPage("configure-the-sftp-receiver-adapter-4ea5d8e", "SFTP");

        assertThat(again).contains("(downloaded now)");
        assertThat(downloads).hasValue(2);
        assertThat(storedChunks()).isEqualTo(chunks);
    }

    @Test
    void onlyCatalogPagesCanBeReadAndAMissingPageIsReported() {
        assertThat(tools.readSapHelpPage("https://evil.example.com/x", "anything"))
                .startsWith("Unknown page id https://evil.example.com/x.");
        assertThat(tools.readSapHelpPage("moved-page-1234567", "anything"))
                .startsWith("The SAP Help page A Page That Moved is no longer available");
        assertThat(downloads).hasValue(0);
        assertThat(storedChunks()).isZero();
    }

    @Test
    void theRealCatalogLoads() {
        var catalog = new SapHelpCatalog(new BagOfWords(), "", "");

        assertThat(catalog.size()).isGreaterThan(1500);
        assertThat(catalog.page("define-exception-subprocess-690e078")).hasValueSatisfying(page -> {
            assertThat(page.title()).isEqualTo("Define Exception Subprocess");
            assertThat(page.url()).isEqualTo(SapHelpCatalog.REPO_BLOB
                    + "docs/ISuite_Integrations_APIs/define-exception-subprocess-690e078.md");
        });
    }
}
