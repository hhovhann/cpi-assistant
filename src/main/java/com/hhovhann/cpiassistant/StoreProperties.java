package com.hhovhann.cpiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the vectors live, bound from {@code cpi.store.*}.
 *
 * @param type     {@code memory}: rebuilt on every start, gone on shutdown.
 *                 {@code pgvector}: Postgres (docker-compose.yml), survives
 *                 restarts — needed once the store holds more than the local
 *                 docs, which can always be re-read from disk
 * @param pgvector connection and table, used only when type is pgvector
 */
@ConfigurationProperties("cpi.store")
public record StoreProperties(
        @DefaultValue("memory") Type type,
        @DefaultValue PgVector pgvector) {

    public enum Type { MEMORY, PGVECTOR }

    /**
     * @param dimension must match the embedding model: nomic-embed-text-v1.5
     *                  makes 768-dimensional vectors, and the column is created
     *                  with that size
     */
    public record PgVector(
            @DefaultValue("localhost") String host,
            @DefaultValue("5433") int port,
            @DefaultValue("cpi") String database,
            @DefaultValue("cpi") String user,
            @DefaultValue("cpi") String password,
            @DefaultValue("cpi_chunks") String table,
            @DefaultValue("768") int dimension) {
    }
}
