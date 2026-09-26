package com.hhovhann.cpiassistant;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge path without the internet or an LLM: a local HTTP server
 * plays SAP's GitHub repository, a bag-of-words fake plays the embedding model.
 */
class KnowledgeServiceTest {

    private static final String SFTP_PATH = "docs/ISuite_Integrations_APIs/configure-the-sftp-receiver-adapter-4ef52cf.md";
    private static final String SFTP_PAGE = """
            <!-- loio4ef52cf -->
            <a name="loio4ef52cf"/>
            # Configure the SFTP Receiver Adapter

            The SFTP receiver adapter connects to an SFTP server. Maintain the known hosts \
            file on the tenant, and allow port 22 in the Cloud Connector. See \
            [Handle Errors Gracefully](handle-errors-gracefully-42c95f7.md) for failures.
            ![Diagram](images/sftp.png)
            """;

    private HttpServer github;
    private final AtomicInteger downloads = new AtomicInteger();
    private final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-26T10:00:00Z"));
    private InMemoryEmbeddingStore<TextSegment> store;
    private KnowledgeService knowledge;

    @BeforeEach
    void setUp() throws IOException {
        github = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        github.createContext("/", exchange -> {
            byte[] body = SFTP_PAGE.getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().equals("/" + SFTP_PATH)) {
                downloads.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        github.start();

        var model = new TestSupport.BagOfWords();
        store = new InMemoryEmbeddingStore<>();
        // A low floor: bag-of-words scores are lower than nomic's.
        var retrieval = new RetrievalService(model, store, "", 0.6);
        var pipeline = new IngestionPipeline(new IngestionProperties(500, 50), model, "");
        var catalog = new SapHelpCatalog(List.of(
                new SapHelpCatalog.Page(SFTP_PATH, "Configure the SFTP Receiver Adapter"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/handle-errors-gracefully-42c95f7.md", "Handle Errors Gracefully"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/moved-sftp-page-1234567.md", "SFTP Page That Moved")),
                model, "", "");
        var client = new SapHelpClient("http://localhost:" + github.getAddress().getPort());
        knowledge = new KnowledgeService(retrieval, catalog, client, pipeline, store, 0.82, 1, Duration.ofDays(30), clock);
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
    void aMissDownloadsTheBestPageSavesItAndAnswersFromIt() {
        var found = knowledge.find("SFTP receiver adapter known hosts");

        assertThat(found.downloaded()).containsExactly("Configure the SFTP Receiver Adapter");
        assertThat(found.passages()).isNotEmpty();
        assertThat(found.passages().getFirst().embedded().text()).contains("known hosts", "See Handle Errors Gracefully for failures")
                .doesNotContain("<!--", "<a name", "](", "![");
        assertThat(found.passages().getFirst().embedded().metadata().getString(KnowledgeService.URL))
                .isEqualTo(SapHelpCatalog.REPO_BLOB + SFTP_PATH);
        assertThat(found.steps()).first().asString().startsWith("Database: nothing above");
        assertThat(found.steps()).anyMatch(step -> step.startsWith("SAP Help: downloaded \"Configure the SFTP Receiver Adapter\""));
        assertThat(downloads).hasValue(1);
    }

    @Test
    void theSecondTimeTheDatabaseAnswersWithoutADownload() {
        knowledge.find("SFTP receiver adapter known hosts");
        int chunks = storedChunks();

        var again = knowledge.find("SFTP receiver adapter known hosts");

        assertThat(again.downloaded()).isEmpty();
        assertThat(again.steps()).singleElement().asString().startsWith("Database: ");
        assertThat(downloads).hasValue(1);
        assertThat(storedChunks()).isEqualTo(chunks);
    }

    @Test
    void anOffTopicQuestionDownloadsNothing() {
        var found = knowledge.find("What is the capital of France?");

        assertThat(found.passages()).isEmpty();
        assertThat(found.steps()).last().asString().startsWith("SAP Help: no page title matches well enough");
        assertThat(downloads).hasValue(0);
    }

    @Test
    void aPageOlderThanMaxAgeIsDownloadedAgainAndReplaced() {
        var page = new SapHelpCatalog.Page(SFTP_PATH, "Configure the SFTP Receiver Adapter");
        assertThat(knowledge.ensureSaved(page)).isEqualTo(KnowledgeService.Saved.DOWNLOADED);
        assertThat(knowledge.ensureSaved(page)).isEqualTo(KnowledgeService.Saved.ALREADY_SAVED);
        int chunks = storedChunks();

        clock.now = clock.now.plus(Duration.ofDays(31));

        assertThat(knowledge.ensureSaved(page)).isEqualTo(KnowledgeService.Saved.DOWNLOADED);
        assertThat(downloads).hasValue(2);
        assertThat(storedChunks()).isEqualTo(chunks);
    }

    @Test
    void aPageThatMovedIsReportedNotSaved() {
        var moved = new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/moved-sftp-page-1234567.md", "SFTP Page That Moved");

        assertThat(knowledge.ensureSaved(moved)).isEqualTo(KnowledgeService.Saved.MISSING);
        assertThat(storedChunks()).isZero();
    }

    @Test
    void passagesAreLabelledByPageTitle() {
        var found = knowledge.find("SFTP receiver adapter known hosts");

        assertThat(KnowledgeService.format(found.passages())).startsWith("[Configure the SFTP Receiver Adapter]\n");
        assertThat(KnowledgeService.format(List.of())).isEqualTo("(no documentation found)");
    }

    @Test
    void theRealCatalogLoads() {
        var catalog = new SapHelpCatalog(new TestSupport.BagOfWords(), "", "");

        assertThat(catalog.size()).isGreaterThan(1500);
        assertThat(catalog.page("define-exception-subprocess-690e078")).hasValueSatisfying(page -> {
            assertThat(page.title()).isEqualTo("Define Exception Subprocess");
            assertThat(page.url()).isEqualTo(SapHelpCatalog.REPO_BLOB
                    + "docs/ISuite_Integrations_APIs/define-exception-subprocess-690e078.md");
        });
        assertThat(catalog.pageByTitle("JDBC Receiver Adapter")).isPresent();
        assertThat(catalog.page("https://evil.example.com/x")).isEmpty();
    }
}
