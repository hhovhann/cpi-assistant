package com.hhovhann.cpiassistant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query-time half of RAG: turn a question into a vector and find the segments
 * whose vectors point in the most similar direction.
 * <p>
 * Kept separate from {@link IngestionPipeline} on purpose — ingestion runs once
 * per page and is slow, retrieval runs per question and must be fast. Same
 * embedding model on both sides, which is why it is injected rather than built.
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
     * Embeds the question and returns the closest chunks at or above the floor.
     *
     * .score() is not raw cosine similarity: both stores report
     * (cosine + 1) / 2, so 0.5 means "cosine 0" and filters nothing useful.
     *
     * nomic-embed-text-v1.5 is trained with asymmetric task prefixes —
     * "search_query: " here, "search_document: " in IngestionPipeline. The
     * floor (cpi.retrieval.min-score, 0.80) was measured on the earlier
     * hand-written docs: noise reached 0.82 and some right chunks scored just
     * under 0.80 — it filters everyday noise, not everything. Below it,
     * KnowledgeService goes to SAP Help.
     */
    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        return search(embedQuery(query), maxResults, embeddingStore, minScore);
    }

    /**
     * The best chunks among those matching a metadata filter — e.g. one saved
     * SAP Help page — with no score floor: the caller already chose the page.
     */
    public List<EmbeddingMatch<TextSegment>> searchWithin(String query, int maxResults, Filter filter) {
        return embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embedQuery(query))
                .maxResults(maxResults)
                .minScore(0.0)
                .filter(filter)
                .build()).matches();
    }

    /** Embeds a question the way search does, with the query prefix. */
    Embedding embedQuery(String query) {
        return embeddingModel.embed(queryPrefix + query).content();
    }

    /**
     * Search a given store by an already-embedded question, with a given
     * floor. The evaluation embeds each question once and searches one store
     * per chunk-size configuration, with floor 0 to see every score.
     */
    List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int maxResults,
                                             EmbeddingStore<TextSegment> store, double minScore) {
        EmbeddingSearchRequest embeddingSearchRequest =
            EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        return store.search(embeddingSearchRequest).matches();
    }

    /** The configured relevance floor. */
    double minScore() {
        return minScore;
    }
}
