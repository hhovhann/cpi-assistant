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
    private static final String JDBC_PATH = "docs/ISuite_Integrations_APIs/jdbc-receiver-adapter-88be644.md";
    private static final String DRIVERS_PATH = "docs/ISuite_Integrations_APIs/configure-jdbc-drivers-77c7d95.md";
    private static final String MARIADB_PATH = "docs/ISuite_Integrations_APIs/jdbc-for-mariadb-cloud-1d320d6.md";
    private static final String JDBC_PAGE = """
            # JDBC Receiver Adapter

            The JDBC receiver adapter connects to databases. Your administrator uploads the JDBC \
            drivers first: see [Configure JDBC Drivers](configure-jdbc-drivers-77c7d95.md).
            """;
    private static final String DRIVERS_PAGE = """
            # Configure JDBC Drivers

            Upload the JDBC driver jar in the JDBC Material tab, then deploy the JDBC drivers.
            """;
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
    private RetrievalService retrieval;
    private SapHelpCatalog catalog;
    private SapHelpClient client;
    private IngestionPipeline pipeline;
    // The JDBC page links to both; its text names only the drivers page.
    private final PageGraph graph = new PageGraph(List.of(
            new String[]{"jdbc-receiver-adapter-88be644", "configure-jdbc-drivers-77c7d95"},
            new String[]{"jdbc-receiver-adapter-88be644", "jdbc-for-mariadb-cloud-1d320d6"}));

    @BeforeEach
    void setUp() throws IOException {
        github = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        github.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring(1);
            String page = path.equals(SFTP_PATH) ? SFTP_PAGE : path.equals(JDBC_PATH) ? JDBC_PAGE
                    : path.equals(DRIVERS_PATH) ? DRIVERS_PAGE : path.equals(MARIADB_PATH) ? "# JDBC for MariaDB\n\nMariaDB JDBC." : null;
            byte[] body = page == null ? new byte[0] : page.getBytes(StandardCharsets.UTF_8);
            if (page != null) {
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
        retrieval = new RetrievalService(model, store, "", 0.6);
        pipeline = new IngestionPipeline(new IngestionProperties(500, 50), model, "");
        catalog = new SapHelpCatalog(List.of(
                new SapHelpCatalog.Page(JDBC_PATH, "JDBC Receiver Adapter"),
                new SapHelpCatalog.Page(DRIVERS_PATH, "Configure JDBC Drivers"),
                new SapHelpCatalog.Page(MARIADB_PATH, "JDBC for MariaDB (Cloud)"),
                new SapHelpCatalog.Page(SFTP_PATH, "Configure the SFTP Receiver Adapter"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/handle-errors-gracefully-42c95f7.md", "Handle Errors Gracefully"),
                new SapHelpCatalog.Page("docs/ISuite_Integrations_APIs/moved-sftp-page-1234567.md", "SFTP Page That Moved")),
                model, "", "");
        client = new SapHelpClient("http://localhost:" + github.getAddress().getPort());
        knowledge = knowledge(0.82, true);
    }

    private KnowledgeService knowledge(double minScore, boolean followLinks) {
        return new KnowledgeService(retrieval, catalog, client, graph, pipeline, store, minScore, 1, Duration.ofDays(30), followLinks, clock);
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
    void askingSapHelpAgainSkipsPagesAlreadySaved() {
        // Both SFTP pages match; the first is saved already. A retry must bring the other.
        knowledge.ensureSaved(new SapHelpCatalog.Page(SFTP_PATH, "Configure the SFTP Receiver Adapter"));

        var retry = knowledge.fetchFromSapHelp("SFTP receiver adapter known hosts");

        assertThat(retry.steps()).noneMatch(step -> step.contains("is already saved"));
        assertThat(retry.steps()).anyMatch(step -> step.startsWith("SAP Help: no new page matches")
                || step.startsWith("SAP Help: downloaded"));
    }

    @Test
    void aDeclineIsRecognisedWithEitherApostrophe() {
        assertThat(KnowledgeService.isIDontKnow("I don't know.")).isTrue();
        assertThat(KnowledgeService.isIDontKnow("  I don\u2019t know the steps.")).isTrue();
        assertThat(KnowledgeService.isIDontKnow("Use the JDBC adapter.")).isFalse();
    }

    @Test
    void passagesAreLabelledByPageTitle() {
        var found = knowledge.find("SFTP receiver adapter known hosts");

        assertThat(KnowledgeService.format(found.passages())).startsWith("[Configure the SFTP Receiver Adapter]\n");
        assertThat(KnowledgeService.format(List.of())).isEqualTo("(no documentation found)");
    }

    @Test
    void aLinkedPageThePassageNamesIsFollowedAndSaved() {
        // Bag-of-words scores are lower than nomic's: a lower bar for this fake.
        var graphAware = knowledge(0.6, true);
        graphAware.ensureSaved(new SapHelpCatalog.Page(JDBC_PATH, "JDBC Receiver Adapter"));

        var found = graphAware.find("JDBC receiver adapter drivers");

        assertThat(found.steps()).anyMatch(step -> step.startsWith("Graph: a passage links to \"Configure JDBC Drivers\""));
        assertThat(found.downloaded()).containsExactly("Configure JDBC Drivers");
        assertThat(found.passages()).extracting(m -> m.embedded().metadata().getString(KnowledgeService.TITLE))
                .contains("JDBC Receiver Adapter", "Configure JDBC Drivers")
                // Linked, but not named in the passage: not followed.
                .doesNotContain("JDBC for MariaDB (Cloud)");
    }

    @Test
    void followingLinksCanBeSwitchedOff() {
        var vectorsOnly = knowledge(0.6, false);
        vectorsOnly.ensureSaved(new SapHelpCatalog.Page(JDBC_PATH, "JDBC Receiver Adapter"));

        var found = vectorsOnly.find("JDBC receiver adapter drivers");

        assertThat(found.steps()).noneMatch(step -> step.startsWith("Graph:"));
        assertThat(downloads).hasValue(1);
    }

    @Test
    void theRealGraphLoads() {
        var real = new PageGraph();

        assertThat(real.edges()).isGreaterThan(3000);
        assertThat(real.linksFrom("jdbc-receiver-adapter-88be644")).contains("configure-jdbc-drivers-77c7d95");
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
