package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.IntegrationRuntimeArtifact;
import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Tools that read the CPI tenant: what is deployed, what failed, and why.
 * Live data, unlike {@link CpiDocsTool}, which knows how CPI works in general.
 * <p>
 * Every tool is read-only, and every result is plain text written for the
 * model: short lines, dates in UTC, and an explicit sentence when there is
 * nothing to report, so an empty result is never mistaken for a broken call.
 */
@Component
public class CpiTenantTools {

    static final int MAX_MESSAGES = 20;
    /** Statuses that mean something went wrong. PROCESSING and COMPLETED do not. */
    static final List<String> PROBLEM_STATUSES = List.of("FAILED", "RETRY", "ESCALATED");
    static final int MAX_ERROR_CHARS = 2000;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** Documentation passages added to an error: enough for the cause and the fix, not a whole page. */
    static final int ERROR_PASSAGES = 4;

    private final CpiTenantClient client;
    private final KnowledgeService knowledge;
    private final Clock clock;

    @Autowired
    public CpiTenantTools(CpiTenantClient client, KnowledgeService knowledge) {
        this(client, knowledge, Clock.systemUTC());
    }

    /** @param knowledge null: errors come without documentation (tests) */
    CpiTenantTools(CpiTenantClient client, KnowledgeService knowledge, Clock clock) {
        this.client = client;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    @Tool("""
            Lists the iFlows deployed on the SAP CPI tenant with their version, \
            deployment status (STARTED, ERROR, ...) and deployment time. Use it to find \
            the exact name of an iFlow, or to check whether its deployment succeeded. \
            It does NOT show whether messages are failing: a STARTED iFlow can still \
            fail on every message. For failing, retrying or broken messages use \
            getProblemMessages.""")
    public String listIflows() {
        List<IntegrationRuntimeArtifact> artifacts = client.runtimeArtifacts();
        if (artifacts.isEmpty()) {
            return "No iFlows are deployed on the tenant.";
        }
        // Measured: asked "is X failing?", the model read STARTED here and stopped.
        return artifacts.stream()
                .map(a -> "%s | version %s | %s | deployed %s".formatted(
                        a.name(), a.version(), a.status(), format(CpiODataModel.fromODataDate(a.deployedOn()))))
                .collect(Collectors.joining("\n"))
                + "\nThis is deployment status only. Whether messages are failing or retrying: call getProblemMessages.";
    }

    @Tool("""
            Lists messages with problems on the SAP CPI tenant, newest first, with the \
            status, iFlow, time, message id, sender and receiver. Statuses: FAILED \
            (stopped with an error), RETRY (failed, and CPI is still retrying it), \
            ESCALATED (needs manual action). Use it for questions about what went wrong \
            or is failing on the tenant. Then call getErrorDetails with a message id to \
            see why.""")
    public String getProblemMessages(
            @P(value = "Exact iFlow name, e.g. Order_Sync. Leave empty for all iFlows.", required = false) String iflowName,
            @P(value = "FAILED, RETRY or ESCALATED — one, or several separated by commas. Leave empty for all three.", required = false) String status,
            @P(value = "How many hours back to look. Default 24.", required = false) Integer hoursBack) {
        List<String> statuses = status == null || status.isBlank()
                ? PROBLEM_STATUSES
                : Arrays.stream(status.toUpperCase(Locale.ROOT).split("[,\\s]+")).filter(s -> !s.isBlank()).distinct().toList();
        if (!PROBLEM_STATUSES.containsAll(statuses)) {
            return "Unknown status " + status + ". Use FAILED, RETRY or ESCALATED — one, several separated by commas, or empty for all.";
        }
        int hours = hoursBack == null || hoursBack <= 0 ? 24 : hoursBack;
        Instant since = clock.instant().minus(Duration.ofHours(hours));

        // One query per status: plain "Status eq" filters, which every OData
        // server understands, merged here instead of an "or" in the filter.
        List<MessageProcessingLog> messages = statuses.stream()
                .flatMap(s -> client.messages(s, iflowName, since, MAX_MESSAGES).stream())
                .sorted(Comparator.comparing((MessageProcessingLog log) -> CpiODataModel.fromODataDate(log.logEnd())).reversed())
                .limit(MAX_MESSAGES)
                .toList();

        boolean oneIflow = iflowName != null && !iflowName.isBlank();
        String scope = (oneIflow ? iflowName : "any iFlow") + " in the last " + hours + " hours";
        String looked = String.join(", ", statuses);
        // Measured: after an empty result the model stopped — no "did you mean",
        // and for the whole tenant no word of an iFlow in ERROR. Both come from
        // the deployment list, so the tool adds them itself.
        String context = oneIflow ? unknownIflow(iflowName) : notRunning();
        if (messages.isEmpty()) {
            return "No messages in status " + looked + " for " + scope + "." + context;
        }
        String counts = messages.stream()
                .collect(Collectors.groupingBy(MessageProcessingLog::status, TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        String list = messages.size() + " message(s) with problems for " + scope + " (" + counts + "):\n" + messages.stream()
                .map(log -> "%s | %s | %s | message id %s | %s -> %s".formatted(
                        format(CpiODataModel.fromODataDate(log.logEnd())), statusText(log.status()), log.integrationFlowName(),
                        log.messageGuid(), log.sender(), log.receiver()))
                .collect(Collectors.joining("\n"));
        // Measured: the model reported a RETRY message as "failed".
        return (messages.stream().anyMatch(log -> "RETRY".equals(log.status()))
                ? list + "\nA RETRY message has not failed: CPI is still retrying it. Report it as retrying."
                : list) + context;
    }

    @Tool("""
            Returns the error text CPI recorded for one message with a problem — the \
            exception, and usually the adapter, endpoint or mapping involved — followed by \
            SAP documentation passages about that error, for the cause and the fix. Needs a \
            message id from getProblemMessages.""")
    public String getErrorDetails(@P("The message id, exactly as getProblemMessages returned it") String messageId) {
        return client.errorInformation(messageId)
                .map(error -> error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) + " [truncated]" : error)
                .map(error -> "Error for message " + messageId + ":\n" + error + documentationFor(error))
                .orElse("No error information for message " + messageId + ". Check the id.");
    }

    /**
     * The documentation for an error, found by code, not left to the model.
     * Measured: asked "what does SAP's documentation say about
     * fixing it?", the model never searched the docs after reading the error,
     * and answered the fix from its own knowledge.
     */
    private String documentationFor(String error) {
        if (knowledge == null) {
            return "";
        }
        var passages = knowledge.find(errorQuery(error)).passages();
        if (passages.isEmpty()) {
            return "\n\nNo SAP documentation found for this error.";
        }
        return "\n\nSAP documentation about this error (cite the pages you use):\n"
                + KnowledgeService.format(passages.subList(0, Math.min(ERROR_PASSAGES, passages.size())));
    }

    /** The error without Java package names — "SQLTransientConnectionException: HikariPool-1 …" searches better. */
    static String errorQuery(String error) {
        String plain = error.replaceAll("\\b(?:[a-z][a-z0-9_]*\\.)+([A-Z]\\w*)", "$1").replaceAll("\\s+", " ").strip();
        return plain.length() <= 300 ? plain : plain.substring(0, 300);
    }

    /** For a name that is not deployed: say so, and name the closest deployed iFlow. Empty if it is deployed. */
    private String unknownIflow(String name) {
        List<String> names = client.runtimeArtifacts().stream().map(IntegrationRuntimeArtifact::name).toList();
        if (names.stream().anyMatch(n -> n.equalsIgnoreCase(name.strip()))) {
            return "";
        }
        String closest = names.stream()
                .min(Comparator.comparingInt(n -> distance(n.toLowerCase(Locale.ROOT), name.strip().toLowerCase(Locale.ROOT))))
                .orElse(null);
        return "\nNo iFlow named " + name + " is deployed."
                + (closest != null && distance(closest.toLowerCase(Locale.ROOT), name.strip().toLowerCase(Locale.ROOT)) <= 3
                        ? " Did you mean " + closest + "?" : "")
                + " Deployed: " + String.join(", ", names) + ".";
    }

    /** iFlows whose deployment is not STARTED — a problem even with no failed message. Empty if all run. */
    private String notRunning() {
        List<String> down = client.runtimeArtifacts().stream()
                .filter(a -> !"STARTED".equals(a.status()))
                .map(a -> a.name() + " (" + a.status() + ")")
                .toList();
        return down.isEmpty() ? "" : "\nNot running (deployment): " + String.join(", ", down) + ".";
    }

    /** Edit distance: how many single-character changes turn one name into the other. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            previous = current;
        }
        return previous[b.length()];
    }

    /** RETRY spelled out: CPI is still trying — not failed. */
    static String statusText(String status) {
        return "RETRY".equals(status) ? "RETRY (still retrying, not failed)" : status;
    }

    private static String format(Instant instant) {
        return instant == null ? "unknown time" : TIME.format(instant);
    }
}
