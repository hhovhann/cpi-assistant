package com.hhovhann.cpiassistant;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which SAP Help page links to which: {@code sap-help/links.tsv}, built with
 * the catalog by scripts/build_sap_help_catalog.py from the links inside the
 * pages — ~3,400 edges between ~1,400 pages. A graph of the documentation,
 * next to the vectors: {@link KnowledgeService} follows one edge when a
 * passage points to a page that answers the question better.
 */
@Component
public class PageGraph {

    private final Map<String, Set<String>> linksFrom;

    public PageGraph() {
        this(load());
    }

    PageGraph(List<String[]> edges) {
        this.linksFrom = new HashMap<>();
        edges.forEach(edge -> linksFrom.computeIfAbsent(edge[0], id -> new LinkedHashSet<>()).add(edge[1]));
    }

    private static List<String[]> load() {
        try {
            String tsv = new ClassPathResource("sap-help/links.tsv").getContentAsString(StandardCharsets.UTF_8);
            return tsv.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t", 2))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sap-help/links.tsv", e);
        }
    }

    /** The page ids a page links to; empty if none. */
    public Set<String> linksFrom(String pageId) {
        return linksFrom.getOrDefault(pageId, Set.of());
    }

    public int edges() {
        return linksFrom.values().stream().mapToInt(Set::size).sum();
    }
}
