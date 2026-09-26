package com.hhovhann.cpiassistant;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pgvector store against a real Postgres with pgvector, in a throwaway
 * container (needs Docker). No LLM: three-dimensional vectors stand in for
 * embeddings, so the scores are predictable.
 */
@Testcontainers
class PgVectorStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    /** A fresh table per test, so tests cannot see each other's rows. */
    private static StoreProperties.PgVector table(String name) {
        return new StoreProperties.PgVector(POSTGRES.getHost(), POSTGRES.getFirstMappedPort(),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword(), name, 3);
    }

    private static String uniqueTable() {
        return "t_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static TextSegment segment(String text, String source) {
        return TextSegment.from(text, Metadata.from("file_name", text + ".txt").put(IngestionPipeline.SOURCE, source));
    }

    private static List<EmbeddingMatch<TextSegment>> everything(EmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1, 0, 0})).maxResults(100).minScore(0.0).build()).matches();
    }

    /** Embeds every text as the same vector, and counts the calls. */
    private static final class FixedEmbeddingModel implements EmbeddingModel {
        int calls;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls++;
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1, 0, 0})).toList());
        }

        @Override
        public int dimension() {
            return 3;
        }
    }

    @Test
    void scoresAreOnTheSameScaleAsTheInMemoryStore() {
        var pg = LangChain4jConfig.pgVectorStore(table(uniqueTable()));
        var memory = new InMemoryEmbeddingStore<TextSegment>();
        var vectors = List.of(Embedding.from(new float[]{1, 0, 0}), Embedding.from(new float[]{0.6f, 0.8f, 0}),
                Embedding.from(new float[]{0, 1, 0}));
        var segments = List.of(segment("same", "x"), segment("close", "x"), segment("orthogonal", "x"));
        pg.addAll(vectors, segments);
        memory.addAll(vectors, segments);

        var pgScores = everything(pg).stream().map(EmbeddingMatch::score).toList();
        var memoryScores = everything(memory).stream().map(EmbeddingMatch::score).toList();

        // (cosine + 1) / 2: 1.0 for the same direction, 0.8 for cosine 0.6, 0.5 for orthogonal.
        assertThat(pgScores).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(pgScores.get(i)).isCloseTo(memoryScores.get(i), org.assertj.core.data.Offset.offset(1e-6));
        }
        assertThat(pgScores.getFirst()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void reingestingReplacesTheLocalDocsAndKeepsEverythingElse() {
        var store = LangChain4jConfig.pgVectorStore(table(uniqueTable()));
        // Something that did not come from the files — later, a saved web page.
        store.add(Embedding.from(new float[]{1, 0, 0}), segment("saved-page", "web"));
        var pipeline = new IngestionPipeline(new IngestionProperties(500, 50), new FixedEmbeddingModel(), store, "");
        var docs = List.of(TextSegment.from("JDBC", Metadata.from("file_name", "02-jdbc-adapter.txt")),
                TextSegment.from("AS2", Metadata.from("file_name", "08-as2.txt")));

        pipeline.embed(docs);
        pipeline.embed(docs); // a restart: the same files again

        var all = everything(store);
        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(m -> IngestionPipeline.LOCAL_DOCS.equals(m.embedded().metadata().getString(IngestionPipeline.SOURCE)))
                .extracting(m -> m.embedded().text()).containsExactlyInAnyOrder("JDBC", "AS2");
        assertThat(all).extracting(m -> m.embedded().text()).contains("saved-page");
    }

    @Test
    void rowsSurviveANewStoreOnTheSameTable() {
        String name = uniqueTable();
        LangChain4jConfig.pgVectorStore(table(name)).add(Embedding.from(new float[]{1, 0, 0}), segment("kept", "web"));

        // A new store object on the same table: what the app sees after a restart.
        var afterRestart = LangChain4jConfig.pgVectorStore(table(name));

        assertThat(everything(afterRestart)).extracting(m -> m.embedded().text()).containsExactly("kept");
        assertThat(everything(afterRestart).getFirst().embedded().metadata().getString("file_name")).isEqualTo("kept.txt");
    }
}
