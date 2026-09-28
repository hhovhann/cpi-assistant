package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Not a test: a measurement of the agent's tool use, run by hand
 * ({@code ./gradlew measure}, report in {@code build/measure/tool-use.md}).
 * Questions a documentation lookup alone cannot answer — the live tenant, a
 * tenant error plus its fix in the docs, a parameter deep in a page — against
 * the fake tenant's planted failures, whose real answers are known.
 * <p>
 * Two configurations, same tools and questions: the agent <b>with skills</b>
 * and <b>without skills</b>, to see what the playbooks add. Per answer:
 * whether the expected tools were called, whether it is right (key facts, no
 * invented failure), unverified citations, and model calls and tokens counted
 * at the model.
 */
@Tag("measure")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18081",
        "cpi.tenant.fake=true",
        "cpi.agent.skills-enabled=true",
        "cpi.tenant.base-url=http://localhost:18081/fake-cpi/api/v1",
        "cpi.store.type=pgvector",
        "cpi.store.pgvector.table=cpi_chunks_measure",
        "cpi.knowledge.seed-on-startup=true",
        "cpi.chat.log-requests=false",
        "cpi.chat.log-responses=false"})
@Import(RagVsFileSearchMeasurement.CountingConfig.class)
class ToolUseMeasurement {

    private static final Path REPORT = Path.of("build/measure/tool-use.md");
    private static final List<String> TENANT_TOOLS = List.of("listIflows", "getProblemMessages", "getErrorDetails");

    /**
     * @param tools     every group needs one of its tools called; empty: none required
     * @param noTools   the right behaviour is to call no tool at all
     * @param facts     every group needs one of its words in a right answer
     * @param forbidden words that mean an invented failure
     * @param cites     a right answer cites a documentation page (the fix of a tenant error)
     */
    record Question(String text, List<List<String>> tools, boolean noTools, List<List<String>> facts, List<String> forbidden,
                    boolean cites) {
    }

    private static final List<Question> QUESTIONS = List.of(
            new Question("Why did Order_Sync fail today and how do I fix it?",
                    List.of(List.of("getProblemMessages"), List.of("getErrorDetails")), false,
                    List.of(List.of("hikari", "connection pool", "timed out", "timeout")), List.of(), true),
            new Question("Is Payment_Status_Poll failing?",
                    List.of(List.of("getProblemMessages")), false,
                    List.of(List.of("retry"), List.of("sftp", "refused")), List.of(), false),
            new Question("Which iFlows are not running?",
                    List.of(List.of("listIflows")), false,
                    List.of(List.of("material_master_load")), List.of(), false),
            new Question("Why did Order_Synk fail today?",
                    List.of(TENANT_TOOLS), false,
                    List.of(List.of("order_sync")), List.of(), false),
            new Question("Why did Unknown_Flow fail?",
                    List.of(TENANT_TOOLS), false,
                    List.of(), List.of("hikari", "sqltransient", "401", "mapping"), false),
            new Question("Why does Customer_Replicate fail, and what does SAP's documentation say about fixing it?",
                    List.of(List.of("getErrorDetails")), false,
                    List.of(List.of("401", "unauthorized"), List.of("credential")), List.of(), true),
            new Question("What went wrong with Invoice_To_Partner_EDI in the last 24 hours?",
                    List.of(List.of("getProblemMessages"), List.of("getErrorDetails")), false,
                    List.of(List.of("mapping"), List.of("partnerid", "partner id")), List.of(), false),
            new Question("Is anything failing on the tenant right now?",
                    List.of(List.of("listIflows", "getProblemMessages")), false,
                    List.of(List.of("material_master_load"), List.of("order_sync", "customer_replicate", "invoice_to_partner_edi")),
                    List.of(), false),
            new Question("Which parameters must I set in the Kafka receiver adapter, including the topic?",
                    List.of(), false,
                    List.of(List.of("topic")), List.of(), false),
            new Question("What is the capital of France?",
                    List.of(), true,
                    List.of(), List.of(), false));

    record Run(String config, Question question, String answer, List<String> tools, boolean toolsRight, boolean right,
               int unverified, String skill, int inputTokens, int outputTokens, int modelCalls, long millis, String note) {
    }

    @Autowired
    AssistService withSkills;
    @Autowired
    KnowledgeService knowledge;
    @Autowired
    SapHelpCatalog catalog;
    @Autowired
    CpiDocsTool docs;
    @Autowired
    CpiTenantTools tenantTools;
    @Autowired
    CpiTenantClient tenant;
    @Autowired
    McpProperties mcp;
    @Autowired
    List<ToolHook> hooks;
    @Autowired
    RagVsFileSearchMeasurement.CountingChatModel model;

    @Test
    void measure() throws Exception {
        // The same agent without skills: the same tools, an empty skill library.
        SkillLibrary none = new SkillLibrary(List.of());
        AgentTools toolsWithoutSkills = new AgentTools(docs, none, tenantTools, tenant, mcp, hooks, false);
        AssistService withoutSkills = new AssistService(knowledge,
                new LangChain4jConfig().cpiAgent(model, toolsWithoutSkills), catalog);

        model.chat("Say OK.");   // load the model before anything is timed

        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < QUESTIONS.size(); i++) {
            Question question = QUESTIONS.get(i);
            // Alternate who goes first, so neither side always gets the warm cache.
            List<Run> pair = i % 2 == 0
                    ? List.of(run("with skills", withSkills, question), run("without skills", withoutSkills, question))
                    : List.of(run("without skills", withoutSkills, question), run("with skills", withSkills, question));
            pair.forEach(System.out::println);
            runs.addAll(pair);
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(runs));
        System.out.println("\n" + Files.readString(REPORT));
    }

    private Run run(String config, AssistService service, Question question) {
        model.reset();
        long start = System.currentTimeMillis();
        try {
            var answer = service.assist(question.text());
            List<String> tools = answer.toolCalls().stream().map(AssistService.ToolCall::tool).toList();
            String skill = answer.toolCalls().stream().filter(c -> c.tool().equals("loadSkill"))
                    .map(c -> c.arguments().replaceAll(".*\"name\"\\s*:\\s*\"([^\"]+)\".*", "$1")).findFirst().orElse("");
            boolean toolsRight = question.noTools()
                    ? tools.isEmpty()
                    : question.tools().stream().allMatch(group -> group.stream().anyMatch(tools::contains));
            String text = answer.answer().toLowerCase(Locale.ROOT).replace('’', '\'');
            boolean declines = text.strip().startsWith("i don't know") || text.contains("not about") || text.contains("not related");
            boolean right = question.noTools()
                    ? declines
                    : question.facts().stream().allMatch(group -> group.stream().anyMatch(text::contains))
                            && question.forbidden().stream().noneMatch(text::contains)
                            && (!question.cites() || answer.sources().stream().anyMatch(AssistService.Source::cited));
            String note = String.join(" → ", tools.isEmpty() ? List.of("no tool") : answer.toolCalls().stream()
                    .map(c -> c.tool() + " " + c.arguments().replaceAll("\\s+", " ")).toList());
            return new Run(config, question, answer.answer(), tools, toolsRight, right, answer.unverifiedCitations().size(),
                    skill, model.inputTokens.get(), model.outputTokens.get(), model.calls.get(),
                    System.currentTimeMillis() - start, note);
        } catch (RuntimeException e) {
            return new Run(config, question, "(failed: " + e.getMessage() + ")", List.of(), false, false, 0, "",
                    model.inputTokens.get(), model.outputTokens.get(), model.calls.get(),
                    System.currentTimeMillis() - start, "failed: " + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
        }
    }

    private static String report(List<Run> runs) {
        StringBuilder md = new StringBuilder("# Tool use\n\n")
                .append("The agent against the fake tenant's planted failures, two configurations with the same tools: "
                        + "with skills and without. One run per question; the two alternate going first.\n\n")
                .append("Tools: every expected tool was called (off-topic: none). Right: the key facts are there, no "
                        + "invented failure, and for a fix a cited documentation page. Unverified: cited pages the model was never given. "
                        + "Model calls and tokens are counted at the model.\n\n")
                .append("| Question | Config | Tools | Right | Unverified | Skill | Model calls | Tokens in | Tokens out | Time | Tool calls |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run run : runs) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %s | %d | %,d | %,d | %.1f s | %s |%n",
                    run.question().text(), run.config(), run.toolsRight() ? "✅" : "❌", run.right() ? "✅" : "❌",
                    run.unverified(), run.skill().isEmpty() ? "—" : run.skill(), run.modelCalls(), run.inputTokens(),
                    run.outputTokens(), run.millis() / 1000.0, run.note().replace("|", "/")));
        }
        md.append("\n## Totals\n\n| Config | Tools right | Right | Unverified citations | Skills loaded | Model calls | Tokens in | Tokens out | Time |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (String config : List.of("with skills", "without skills")) {
            List<Run> mine = runs.stream().filter(r -> r.config().equals(config)).toList();
            md.append(String.format(Locale.ROOT, "| %s | %d of %d | %d of %d | %d | %d | %d | %,d | %,d | %.0f s |%n", config,
                    mine.stream().filter(Run::toolsRight).count(), mine.size(),
                    mine.stream().filter(Run::right).count(), mine.size(),
                    mine.stream().mapToInt(Run::unverified).sum(),
                    mine.stream().filter(r -> !r.skill().isEmpty()).count(),
                    mine.stream().mapToInt(Run::modelCalls).sum(),
                    mine.stream().mapToInt(Run::inputTokens).sum(), mine.stream().mapToInt(Run::outputTokens).sum(),
                    mine.stream().mapToLong(Run::millis).sum() / 1000.0));
        }
        md.append("\n## Answers\n");
        for (Run run : runs) {
            md.append("\n### %s — %s\n\n%s\n".formatted(run.config(), run.question().text(), run.answer().strip()));
        }
        return md.toString();
    }
}
