package com.hhovhann.cpiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chunking knobs, bound from {@code cpi.ingestion.*}.
 *
 * These live in configuration rather than in code because chunk size is a
 * tuning knob: every combination should be reachable without touching a source
 * file, e.g.
 *
 * <pre>
 * ./gradlew bootRun --args="--cpi.ingestion.max-segment-size=500 --cpi.ingestion.max-overlap-size=50"
 * </pre>
 *
 * @param maxSegmentSize largest segment the splitter will emit, in characters
 * @param maxOverlapSize characters repeated from the end of the previous
 *                       segment, so a sentence crossing a boundary survives
 *                       intact in at least one of the two
 */
@ConfigurationProperties("cpi.ingestion")
public record IngestionProperties(
        @DefaultValue("500") int maxSegmentSize,
        @DefaultValue("50") int maxOverlapSize) {
}
