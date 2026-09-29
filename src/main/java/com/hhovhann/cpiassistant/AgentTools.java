package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every tool the agent can call, in one place: the documentation tools
 * always; skills when {@code cpi.agent.skills-enabled}; the tenant tools when
 * a tenant is configured; and the
 * allowed tools of each MCP server in {@code cpi.mcp.servers}. Each call goes
 * through the {@link ToolHook}s, whatever its source — so a check or a log
 * line is written once and covers everything.
 */
@Component
public class AgentTools implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    static final String BUILTIN = "builtin";

    private final Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
    private final List<McpClient> mcpClients = new ArrayList<>();
    private final List<ToolHook> hooks;

    @Autowired
    public AgentTools(CpiDocsTool docs, SkillLibrary skills, CpiTenantTools tenantTools, CpiTenantClient tenant,
                      McpProperties mcp, List<ToolHook> hooks,
                      @Value("${cpi.agent.skills-enabled:false}") boolean skillsEnabled) {
        this.hooks = hooks;
        addBuiltin(docs);
        if (skillsEnabled) {
            addSkills(skills);
        }
        if (tenant.isConfigured()) {
            addBuiltin(tenantTools);
        } else {
            log.info("No CPI tenant configured (cpi.tenant.base-url): the tenant tools are not offered");
        }
        for (McpProperties.Server server : mcp.servers()) {
            try {
                McpClient client = connect(server);
                mcpClients.add(client);
                addMcp(server, client);
            } catch (RuntimeException e) {
                log.warn("MCP server {} is not available, its tools are not offered: {}", server.name(), e.getMessage());
            }
        }
        log.info("Agent tools: {}", names());
    }

    /** For tests: given tools and hooks, no Spring. */
    AgentTools(Map<ToolSpecification, ToolExecutor> given, List<ToolHook> hooks) {
        this.hooks = hooks;
        given.forEach((spec, executor) -> add(spec, executor, BUILTIN));
    }

    /** What AiServices gets: every tool, each executor wrapped in the hooks. */
    public Map<ToolSpecification, ToolExecutor> tools() {
        return tools;
    }

    public List<String> names() {
        return tools.keySet().stream().map(ToolSpecification::name).toList();
    }

    private void addBuiltin(Object toolObject) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                add(ToolSpecifications.toolSpecificationFrom(method), new DefaultToolExecutor(toolObject, method), BUILTIN);
            }
        }
    }

    /**
     * loadSkill, with the skills listed in its own description. Measured
     * with Qwen3 14B: with the list in the system prompt, the model never called
     * loadSkill and once named a skill as if it were a tool; a tool's
     * description sits next to the tool. No skills: no loadSkill.
     */
    void addSkills(SkillLibrary skills) {
        if (skills.summary().isEmpty()) {
            return;
        }
        for (Method method : skills.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                spec = spec.toBuilder().description(spec.description() + "\nSkills:\n" + skills.summary()).build();
                add(spec, new DefaultToolExecutor(skills, method), BUILTIN);
            }
        }
    }

    /**
     * Only the tools on the server's allowlist, never one that would shadow a
     * tool of ours, and never one whose description addresses the model.
     */
    void addMcp(McpProperties.Server server, McpClient client) {
        for (ToolSpecification spec : client.listTools()) {
            if (!server.allowedTools().contains(spec.name())) {
                continue;
            }
            if (names().contains(spec.name())) {
                log.warn("MCP server {} offers {}, which already exists: skipped", server.name(), spec.name());
                continue;
            }
            // A tool's description goes into the prompt as it is: one that talks
            // to the model is an injection by the server.
            if (UntrustedText.looksLikeInstructions(spec.description())) {
                log.warn("MCP server {} describes {} with instructions to the model: skipped", server.name(), spec.name());
                continue;
            }
            add(spec, (request, memoryId) -> client.executeTool(request).resultText(), server.name());
        }
    }

    private void add(ToolSpecification spec, ToolExecutor executor, String source) {
        tools.put(spec, (request, memoryId) -> run(request, memoryId, executor, source));
    }

    private String run(ToolExecutionRequest request, Object memoryId, ToolExecutor executor, String source) {
        ToolHook.Call call = new ToolHook.Call(request.name(), request.arguments(), source);
        for (ToolHook hook : hooks) {
            String refusal = hook.before(call);
            if (refusal != null) {
                log.info("tool={} source={} refused: {}", call.tool(), source, refusal);
                return refusal;
            }
        }
        long start = System.currentTimeMillis();
        String result;
        try {
            result = executor.execute(request, memoryId);
        } catch (RuntimeException e) {
            // The model reads this and can try something else; the request does not fail.
            result = "The tool failed: " + e.getMessage();
        }
        long millis = System.currentTimeMillis() - start;
        for (ToolHook hook : hooks) {
            result = hook.after(call, result, millis);
        }
        return result;
    }

    private static McpClient connect(McpProperties.Server server) {
        McpTransport transport = server.command().isEmpty()
                ? new StreamableHttpMcpTransport.Builder().url(server.url()).customHeaders(headers(server))
                        .timeout(Duration.ofSeconds(60)).build()
                : new StdioMcpTransport.Builder().command(server.command()).build();
        return new DefaultMcpClient.Builder().key(server.name()).transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(60)).build();
    }

    /** The server's headers, plus a fresh bearer token on every request when it has a token URL. */
    static Supplier<Map<String, String>> headers(McpProperties.Server server) {
        if (server.tokenUrl() == null || server.tokenUrl().isBlank()) {
            return server::headers;
        }
        OAuthClientCredentials oauth = new OAuthClientCredentials(server.tokenUrl(), server.clientId(), server.clientSecret());
        return () -> {
            Map<String, String> headers = new LinkedHashMap<>(server.headers());
            headers.put("Authorization", "Bearer " + oauth.token());
            return headers;
        };
    }

    @Override
    public void destroy() {
        for (McpClient client : mcpClients) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Closing MCP client {}: {}", client.key(), e.getMessage());
            }
        }
    }
}
