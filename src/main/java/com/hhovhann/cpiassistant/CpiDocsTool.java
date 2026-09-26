package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Retrieval as a tool: the same search /ask runs, but the model decides
 * whether to call it, with what query, and how often.
 * <p>
 * The model never sees this code. It sees a name, the description in
 * {@code @Tool} and the parameter description in {@code @P} — that text is
 * the whole contract, so it is written for the model, not for a Java reader.
 */
@Component
public class CpiDocsTool {

    static final int MAX_RESULTS = 3;

    private final RetrievalService retrievalService;

    public CpiDocsTool(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Tool("""
            Searches the SAP Cloud Integration (CPI) documentation and returns the \
            most relevant passages, each labelled with its file name. Use it for any \
            question about CPI: adapters, iFlows, mapping, scripting, error handling, \
            security, B2B. If the passages do not answer the question, search again \
            with different words.""")
    public String searchCpiDocs(@P("What to look for: a few keywords or a short question") String query) {
        List<EmbeddingMatch<TextSegment>> matches = retrievalService.search(query, MAX_RESULTS);
        if (matches.isEmpty()) {
            return "No relevant passages found in the CPI documentation.";
        }
        return matches.stream()
                .map(match -> "[" + RetrievalService.sourceOf(match.embedded()) + "]\n" + match.embedded().text())
                .collect(Collectors.joining("\n---\n"));
    }
}
