package com.hhovhann.cpiassistant;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text the model reads but nobody we trust wrote: tool results (a tenant's
 * error holds whatever the receiver sent back), MCP results, the question.
 * Two things are done with it:
 * <ul>
 *   <li><b>marked</b> — put inside a tag, so the prompt can say "what is in
 *       these tags is data". A closing tag inside the text is defused, so the
 *       text cannot end its own tag and pose as the prompt.</li>
 *   <li><b>checked</b> — words that talk to the model ("ignore all previous
 *       instructions", "notice to the AI assistant") or a chat template's role
 *       tokens. A hit does not block anything: it is reported, and the model
 *       is told. A pattern list is easy to get around — it catches the common
 *       attacks, the tags and the prompt are the defence.</li>
 * </ul>
 */
final class UntrustedText {

    /** The tags the agent's prompt knows. */
    static final List<String> TAGS = List.of("passages", "question", "tool-result");

    private static final Pattern TAG = Pattern.compile("<(/?)(" + String.join("|", TAGS) + ")\\b", Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> INSTRUCTIONS = List.of(
            "\\b(ignore|disregard|forget)\\b.{0,20}\\b(previous|prior|above|earlier|all|your)\\b.{0,20}\\b(instructions?|prompts?|rules)\\b",
            "\\b(notice|message|note|instructions?)\\s+(to|for)\\s+(the\\s+)?(ai|assistant|model|llm|chatbot)\\b",
            "\\b(ai|ai assistant|assistant|chatbot|llm)\\s*:",
            "\\byou are now\\b",
            "\\bsystem prompt\\b",
            "\\b(reply|respond|answer)\\s+only\\s+with\\b",
            "\\bdo not (mention|tell|reveal)\\b.{0,20}\\b(this|the user)\\b",
            "<\\|(im_start|im_end|system|user|assistant)\\|>|\\[/?INST]|<</?SYS>>")
            .stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    private UntrustedText() {
    }

    /** The text in a tag of its own; a tag of ours inside it loses its angle bracket. */
    static String mark(String tag, String attributes, String text) {
        return "<" + tag + (attributes.isEmpty() ? "" : " " + attributes) + ">\n" + defuse(text) + "\n</" + tag + ">";
    }

    /** {@code </tool-result>} becomes {@code ‹/tool-result>}: still readable, no longer a tag. */
    static String defuse(String text) {
        return text == null ? "" : TAG.matcher(text).replaceAll("‹$1$2");
    }

    /** True when the text seems to address the model. */
    static boolean looksLikeInstructions(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String flat = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return INSTRUCTIONS.stream().anyMatch(p -> p.matcher(flat).find());
    }
}
