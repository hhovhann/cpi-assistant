package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The documentation tools: search more, or read one SAP page in full. Only
 * pages of the catalog can be read — the model names a page, never a URL.
 * <p>
 * The model never sees this code, only the name and the text in
 * {@code @Tool} and {@code @P} — that text is written for the model.
 */
@Component
public class CpiDocsTool {

    /** One part of a page: about 3,000 tokens. */
    static final int PART = 12_000;

    private final KnowledgeService knowledge;
    private final SapHelpCatalog catalog;
    private final SapHelpClient client;

    public CpiDocsTool(KnowledgeService knowledge, SapHelpCatalog catalog, SapHelpClient client) {
        this.knowledge = knowledge;
        this.catalog = catalog;
        this.client = client;
    }

    @Tool("""
            Searches the SAP Cloud Integration documentation and returns passages, each \
            under its page title in square brackets. Use it when the passages you were \
            given do not cover what you need — for example the cause and fix of an error \
            text from the tenant.""")
    public String searchDocs(@P("What to look for: a few keywords, or an error text") String query) {
        return KnowledgeService.format(knowledge.find(query).passages());
    }

    @Tool("""
            Reads one SAP documentation page in full, in parts of about 12,000 characters. \
            Use it when a passage comes from the right page but not the part you need — \
            for example the full parameter table of an adapter.""")
    public String readPage(@P("The page title, exactly as it appears in square brackets") String title,
                           @P("Which part to read, starting at 1") int part) {
        Optional<SapHelpCatalog.Page> page = catalog.pageByTitle(title);
        if (page.isEmpty()) {
            return "No page titled \"" + title + "\" in the SAP catalog. Use a title from the passages.";
        }
        Optional<String> text = client.fetch(page.get().path());
        if (text.isEmpty()) {
            return "The page \"" + title + "\" is no longer available.";
        }
        int parts = Math.max(1, (text.get().length() + PART - 1) / PART);
        int n = Math.min(Math.max(part, 1), parts);
        String slice = text.get().substring((n - 1) * PART, Math.min(n * PART, text.get().length()));
        return "[" + page.get().title() + "]\n(part " + n + " of " + parts + ")\n" + slice;
    }
}
