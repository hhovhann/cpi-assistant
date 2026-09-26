package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * More documentation, when the passages the assistant was given are not
 * enough — typically after a tenant tool returned an error to look up. Goes
 * through {@link KnowledgeService}: the store first, SAP Help on a miss.
 * <p>
 * The model never sees this code, only the name and the text in
 * {@code @Tool} and {@code @P} — that text is written for the model.
 */
@Component
public class CpiDocsTool {

    private final KnowledgeService knowledge;

    public CpiDocsTool(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Tool("""
            Searches the SAP Cloud Integration documentation and returns passages, each \
            under its page title in square brackets. Use it when the passages you were \
            given do not cover what you need — for example to find the cause and fix of \
            an error text from the tenant.""")
    public String searchDocs(@P("What to look for: a few keywords, or an error text") String query) {
        return KnowledgeService.format(knowledge.find(query).passages());
    }
}
