package com.hhovhann.cpiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * MCP servers whose tools the agent may use, bound from {@code cpi.mcp.servers}.
 * None by default, and only official SAP servers belong here — for example an
 * MCP Server created in SAP Integration Suite. Each server exposes only the
 * tools on its allowlist: an MCP server can offer tools that write or reach
 * anywhere, and none of that reaches the model unless it is named here.
 *
 * @param servers the servers, each started (stdio) or reached (HTTP) at startup
 */
@ConfigurationProperties("cpi.mcp")
public record McpProperties(@DefaultValue List<Server> servers) {

    /**
     * @param name         a short name, shown in the path and the audit log
     * @param command      for a local server over stdio, e.g. [npx, -y, some-mcp-server]
     * @param url          for a remote server over streamable HTTP; used when no command is set
     * @param headers      extra HTTP headers
     * @param tokenUrl     OAuth token URL from the server's BTP service key; with it, every request carries
     *                     a bearer token (client credentials, refreshed before it expires)
     * @param clientId     from an environment variable, never committed
     * @param clientSecret from an environment variable, never committed
     * @param allowedTools the tools of this server the model may call; empty means none
     */
    public record Server(String name, @DefaultValue List<String> command, String url,
                         @DefaultValue Map<String, String> headers, String tokenUrl, String clientId, String clientSecret,
                         @DefaultValue List<String> allowedTools) {
    }
}
