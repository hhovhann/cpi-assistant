package com.hhovhann.cpiassistant;

/**
 * Runs around every tool call — ours and every MCP server's — like hooks in
 * Claude Code. {@link AgentTools} applies all hooks in order.
 */
public interface ToolHook {

    /**
     * @param tool      the tool name the model called
     * @param arguments the JSON arguments the model sent
     * @param source    "builtin", or the MCP server's name
     */
    record Call(String tool, String arguments, String source) {
    }

    /** Before the call: null to allow it, or the reason it is refused — the model gets that text instead. */
    default String before(Call call) {
        return null;
    }

    /** After the call: the result, possibly changed (trimmed, masked). */
    default String after(Call call, String result, long millis) {
        return result;
    }
}
