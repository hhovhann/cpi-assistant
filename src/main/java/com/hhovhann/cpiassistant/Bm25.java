package com.hhovhann.cpiassistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25, the classic keyword ranking: a page scores for each query word it
 * contains, more for rare words (IDF), with diminishing returns for repeats and
 * a penalty for length. The keyword half of hybrid search — exact words like
 * "known hosts" or "AS4", which the vector half blurs.
 */
final class Bm25 {

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Set<String> STOP = Set.of("a", "an", "and", "are", "can", "do", "does", "for", "from",
            "how", "i", "in", "is", "it", "my", "of", "on", "or", "the", "to", "what", "with", "which", "why", "we");
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    record Scored(String id, double score) {
    }

    private final Map<String, Map<String, Integer>> termCounts = new HashMap<>();
    private final Map<String, Integer> lengths = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    Bm25(Map<String, String> documents) {
        long total = 0;
        for (Map.Entry<String, String> document : documents.entrySet()) {
            Map<String, Integer> counts = new HashMap<>();
            int length = 0;
            Matcher m = TOKEN.matcher(document.getValue().toLowerCase(Locale.ROOT));
            while (m.find()) {
                counts.merge(m.group(), 1, Integer::sum);
                length++;
            }
            termCounts.put(document.getKey(), counts);
            lengths.put(document.getKey(), length);
            counts.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            total += length;
        }
        averageLength = documents.isEmpty() ? 1 : Math.max(1, (double) total / documents.size());
    }

    /** The query's words, lower-cased, without stop words, each once. */
    static List<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!STOP.contains(m.group())) {
                terms.add(m.group());
            }
        }
        return new ArrayList<>(terms);
    }

    /** Documents containing at least one query word, best first. */
    List<Scored> rank(String query, int maxResults) {
        List<String> terms = terms(query);
        int n = termCounts.size();
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> document : termCounts.entrySet()) {
            double score = 0;
            for (String term : terms) {
                int tf = document.getValue().getOrDefault(term, 0);
                if (tf > 0) {
                    int df = documentFrequency.get(term);
                    double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                    score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths.get(document.getKey()) / averageLength));
                }
            }
            if (score > 0) {
                scored.add(new Scored(document.getKey(), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(maxResults, scored.size()));
    }

    /**
     * Reciprocal Rank Fusion: each list gives an item 1 / (k + its rank); the
     * sums order the merged list. Only ranks matter, so a similarity (0.8–0.95)
     * and a BM25 score (0–30) can be combined without converting one into the
     * other. k = 60 is the usual constant.
     */
    static List<String> fuse(List<List<String>> rankings) {
        Map<String, Double> fused = new HashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                fused.merge(ranking.get(rank), 1.0 / (60 + rank + 1), Double::sum);
            }
        }
        List<String> ids = new ArrayList<>(fused.keySet());
        ids.sort((a, b) -> Double.compare(fused.get(b), fused.get(a)));
        return ids;
    }
}
