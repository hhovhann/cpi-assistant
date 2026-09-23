package com.hhovhann.cpiassistant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query-time half of RAG: turn a question into a vector and find the segments
 * whose vectors point in the most similar direction.
 * <p>
 * Kept separate from {@link IngestionPipeline} on purpose — ingestion runs once
 * and is slow, retrieval runs per question and must be fast. Same embedding
 * model on both sides, though, which is why it is injected rather than built.
 */
@Service
public class RetrievalService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String queryPrefix;
    private final double minScore;

    public RetrievalService(EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            @Value("${langchain4j.open-ai.embedding-model.query-prefix:}") String queryPrefix,
                            @Value("${cpi.retrieval.min-score:0.80}") double minScore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.queryPrefix = queryPrefix;
        this.minScore = minScore;
    }

    /**
     * Embeds the question and returns the closest segments.
     *
     * Note what .score() actually is. It is NOT raw cosine similarity:
     * InMemoryEmbeddingStore reports RelevanceScore.fromCosineSimilarity,
     * which rescales [-1..1] onto [0..1] as (cosine + 1) / 2. A minScore of
     * 0.5 would mean "cosine >= 0.0" and filter nothing useful.
     *
     * nomic-embed-text-v1.5 is trained with asymmetric task prefixes —
     * "search_query: " here, "search_document: " in IngestionPipeline.embed().
     * Measured at (500, 50), without -> with prefixes:
     *   JDBC chunk, database question   0.8168 -> 0.8642
     *   best "capital of France" junk   0.7511 -> 0.7873
     *   weakest real hit (AS2)               -> 0.8661
     * The gap between a right answer and noise widened a little (0.065 -> 0.077),
     * so the floor (cpi.retrieval.min-score) sits at 0.80, in that gap.
     *
     * Still unsolved: the Partner Directory chunk (0.8714) outranks JDBC
     * (0.8642) for "How do I connect to a database from an iFlow?". The
     * question avoids the word "JDBC" on purpose, so pure semantic search
     * has little to go on. JDBC is still in the top 3, which is enough for
     * RagService; fixing the order needs hybrid keyword search or a reranker.
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(queryPrefix + query).content();
        EmbeddingSearchRequest embeddingSearchRequest =
            EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        return embeddingStore.search(embeddingSearchRequest).matches();
    }
}
