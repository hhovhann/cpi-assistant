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
import java.util.Comparator;
import java.util.List;
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

    private final CpiTenantClient client;
    private final Clock clock;

    @Autowired
    public CpiTenantTools(CpiTenantClient client) {
        this(client, Clock.systemUTC());
    }

    CpiTenantTools(CpiTenantClient client, Clock clock) {
        this.client = client;
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
        return artifacts.stream()
                .map(a -> "%s | version %s | %s | deployed %s".formatted(
                        a.name(), a.version(), a.status(), format(CpiODataModel.fromODataDate(a.deployedOn()))))
                .collect(Collectors.joining("\n"));
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
            @P(value = "FAILED, RETRY or ESCALATED. Leave empty for all three.", required = false) String status,
            @P(value = "How many hours back to look. Default 24.", required = false) Integer hoursBack) {
        List<String> statuses;
        if (status == null || status.isBlank()) {
            statuses = PROBLEM_STATUSES;
        } else if (PROBLEM_STATUSES.contains(status.trim().toUpperCase())) {
            statuses = List.of(status.trim().toUpperCase());
        } else {
            return "Unknown status " + status + ". Use FAILED, RETRY or ESCALATED, or leave it empty.";
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

        String scope = (iflowName == null || iflowName.isBlank() ? "any iFlow" : iflowName)
                + " in the last " + hours + " hours";
        String looked = String.join(", ", statuses);
        if (messages.isEmpty()) {
            return "No messages in status " + looked + " for " + scope + ".";
        }
        String counts = messages.stream()
                .collect(Collectors.groupingBy(MessageProcessingLog::status, TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        return messages.size() + " message(s) with problems for " + scope + " (" + counts + "):\n" + messages.stream()
                .map(log -> "%s | %s | %s | message id %s | %s -> %s".formatted(
                        format(CpiODataModel.fromODataDate(log.logEnd())), log.status(), log.integrationFlowName(),
                        log.messageGuid(), log.sender(), log.receiver()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("""
            Returns the error text CPI recorded for one message with a problem: the \
            exception, and usually the adapter, endpoint or mapping involved. Needs a \
            message id from getProblemMessages.""")
    public String getErrorDetails(@P("The message id, exactly as getProblemMessages returned it") String messageId) {
        return client.errorInformation(messageId)
                .map(error -> error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) + " [truncated]" : error)
                .map(error -> "Error for message " + messageId + ":\n" + error)
                .orElse("No error information for message " + messageId + ". Check the id.");
    }

    private static String format(Instant instant) {
        return instant == null ? "unknown time" : TIME.format(instant);
    }
}
