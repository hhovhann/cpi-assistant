package com.hhovhann.cpiassistant;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/** Fakes shared by the tests that must not call LM Studio. */
final class TestSupport {

    private TestSupport() {
    }

    /** Each word hashed into one of 64 buckets: texts that share words land close together. */
    static final class BagOfWords implements EmbeddingModel {
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

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
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
}
