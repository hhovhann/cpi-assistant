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
import java.util.List;
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
            runtime status (STARTED, ERROR, ...) and deployment time. Use it to find \
            the exact name of an iFlow, or to check whether an iFlow is running.""")
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
            Lists messages that FAILED on the SAP CPI tenant, newest first, with the \
            message id, iFlow, time, sender and receiver. Use it for questions about \
            what went wrong on the tenant. Then call getErrorDetails with a message id \
            to see why it failed.""")
    public String getFailedMessages(
            @P(value = "Exact iFlow name, e.g. Order_Sync. Leave empty for all iFlows.", required = false) String iflowName,
            @P(value = "How many hours back to look. Default 24.", required = false) Integer hoursBack) {
        int hours = hoursBack == null || hoursBack <= 0 ? 24 : hoursBack;
        Instant since = clock.instant().minus(Duration.ofHours(hours));
        List<MessageProcessingLog> failed = client.failedMessages(iflowName, since, MAX_MESSAGES);
        String scope = (iflowName == null || iflowName.isBlank() ? "any iFlow" : iflowName) + " in the last " + hours + " hours";
        if (failed.isEmpty()) {
            return "No failed messages for " + scope + ".";
        }
        return failed.size() + " failed message(s) for " + scope + ":\n" + failed.stream()
                .map(log -> "%s | %s | message id %s | %s -> %s".formatted(
                        format(CpiODataModel.fromODataDate(log.logEnd())), log.integrationFlowName(),
                        log.messageGuid(), log.sender(), log.receiver()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("""
            Returns the error text CPI recorded for one failed message: the exception, \
            and usually the adapter, endpoint or mapping involved. Needs a message id \
            from getFailedMessages.""")
    public String getErrorDetails(@P("The message id, exactly as getFailedMessages returned it") String messageId) {
        return client.errorInformation(messageId)
                .map(error -> error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) + " [truncated]" : error)
                .map(error -> "Error for message " + messageId + ":\n" + error)
                .orElse("No error information for message " + messageId + ". Check the id.");
    }

    private static String format(Instant instant) {
        return instant == null ? "unknown time" : TIME.format(instant);
    }
}
