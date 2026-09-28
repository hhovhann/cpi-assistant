package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hooks around every tool call, and which MCP tools get through. No model, no
 * MCP server: a stand-in client plays the server.
 */
class AgentToolsTest {

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("1").name(name).arguments(arguments).build();
    }

    private static String run(AgentTools tools, String name, String arguments) {
        return tools.tools().entrySet().stream().filter(e -> e.getKey().name().equals(name)).findFirst().orElseThrow()
                .getValue().execute(call(name, arguments), null);
    }

    private static Map<ToolSpecification, ToolExecutor> one(String name, ToolExecutor executor) {
        Map<ToolSpecification, ToolExecutor> map = new LinkedHashMap<>();
        map.put(spec(name), executor);
        return map;
    }

    @Test
    void aHookCanRefuseACallAndTheToolNeverRuns() {
        List<String> ran = new ArrayList<>();
        var hooks = new ToolHooks();
        var tools = new AgentTools(one("searchDocs", (r, m) -> { ran.add(r.arguments()); return "ok"; }),
                List.of(hooks.argumentGuard(), hooks.resultLimit()));

        String result = run(tools, "searchDocs", "x".repeat(ToolHooks.MAX_ARGUMENTS + 1));

        assertThat(result).startsWith("Refused: the arguments are longer than");
        assertThat(ran).isEmpty();
        assertThat(run(tools, "searchDocs", "{\"query\":\"JDBC\"}")).isEqualTo("ok");
    }

    @Test
    void aHugeResultIsCutAndHooksRunInOrder() {
        List<String> order = new ArrayList<>();
        ToolHook first = new ToolHook() {
            @Override
            public String after(Call call, String result, long millis) {
                order.add("first:" + result.length());
                return result;
            }
        };
        var tools = new AgentTools(one("readPage", (r, m) -> "y".repeat(ToolHooks.MAX_RESULT + 500)),
                List.of(new ToolHooks().resultLimit(), first));

        String result = run(tools, "readPage", "{}");

        assertThat(result).endsWith("(cut at " + ToolHooks.MAX_RESULT + " characters)");
        assertThat(order).singleElement().asString().isEqualTo("first:" + result.length());
    }

    @Test
    void aFailingToolGivesTheModelTextNotAnError() {
        var tools = new AgentTools(one("getErrorDetails", (r, m) -> { throw new IllegalStateException("tenant down"); }), List.of());

        assertThat(run(tools, "getErrorDetails", "{}")).isEqualTo("The tool failed: tenant down");
    }

    @Test
    void anMcpServerExposesOnlyItsAllowedToolsAndNeverShadowsOurs() {
        var tools = new AgentTools(one("searchDocs", (r, m) -> "ours"), List.of());
        var server = new McpProperties.Server("sap-is", List.of(), "http://unused", Map.of(), null, null, null,
                List.of("sap_search", "searchDocs"));
        McpClient client = fakeMcp(List.of("sap_search", "delete_everything", "searchDocs"), "from mcp");

        tools.addMcp(server, client);

        assertThat(tools.names()).containsExactly("searchDocs", "sap_search");
        assertThat(run(tools, "sap_search", "{\"q\":\"AS4\"}")).isEqualTo("from mcp");
        assertThat(run(tools, "searchDocs", "{}")).isEqualTo("ours");
    }

    /** An MCP client that lists the given tools and answers every call with the same text. */
    static McpClient fakeMcp(List<String> toolNames, String answer) {
        return (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listTools" -> toolNames.stream().map(AgentToolsTest::spec).toList();
                    case "executeTool" -> ToolExecutionResult.builder().resultText(answer).build();
                    case "key" -> "fake";
                    default -> null;
                });
    }

    @Test
    void loadSkillListsEverySkillInItsOwnDescriptionAndIsOfferedOnlyWithSkills() {
        var tools = new AgentTools(Map.of(), List.of());
        tools.addSkills(new SkillLibrary());

        ToolSpecification loadSkill = tools.tools().keySet().stream().filter(s -> s.name().equals("loadSkill")).findFirst().orElseThrow();
        assertThat(loadSkill.description()).contains("Skills:\n- check-tenant-health: ", "- configure-adapter: ", "- troubleshoot-failed-message: ");
        assertThat(run(tools, "loadSkill", "{\"name\":\"check-tenant-health\"}")).startsWith("Skill check-tenant-health:");

        var none = new AgentTools(Map.of(), List.of());
        none.addSkills(new SkillLibrary(List.of()));
        assertThat(none.names()).isEmpty();
    }

    @Test
    void anOfficialSapMcpServerGetsABearerTokenFromItsServiceKey() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            byte[] body = "{\"access_token\":\"mcp-1\",\"expires_in\":3600}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String tokenUrl = "http://localhost:" + server.getAddress().getPort() + "/oauth/token";
            var withKey = new McpProperties.Server("sap-is", List.of(), "http://unused", Map.of("X-Tenant", "t1"),
                    tokenUrl, "client", "secret", List.of());
            var withoutKey = new McpProperties.Server("plain", List.of(), "http://unused", Map.of("X-Tenant", "t1"),
                    null, null, null, List.of());

            assertThat(AgentTools.headers(withKey).get()).containsEntry("Authorization", "Bearer mcp-1").containsEntry("X-Tenant", "t1");
            assertThat(AgentTools.headers(withoutKey).get()).containsOnlyKeys("X-Tenant");
        } finally {
            server.stop(0);
        }
    }
}
