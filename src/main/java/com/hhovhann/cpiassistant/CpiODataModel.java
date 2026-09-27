package com.hhovhann.cpiassistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slice of the SAP Cloud Integration OData API (v1, OData V2) the tools
 * use, shaped exactly like the real JSON — PascalCase names, a
 * {@code {"d": {"results": [...]}}} envelope, dates as {@code /Date(millis)/}.
 * Shared by the client and by the fake tenant, so both speak the same wire
 * format and the client never knows which one it is talking to.
 */
public final class CpiODataModel {

    private CpiODataModel() {
    }

    /** OData V2 wraps every collection: {@code {"d": {"results": [...]}}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ODataList<T>(@JsonProperty("d") Results<T> d) {

        public static <T> ODataList<T> of(List<T> results) {
            return new ODataList<>(new Results<>(results));
        }

        public List<T> results() {
            return d == null || d.results() == null ? List.of() : d.results();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Results<T>(@JsonProperty("results") List<T> results) {
    }

    /** One message run through one iFlow. Status: COMPLETED, FAILED, RETRY, ESCALATED, PROCESSING. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageProcessingLog(
            @JsonProperty("MessageGuid") String messageGuid,
            @JsonProperty("CorrelationId") String correlationId,
            @JsonProperty("IntegrationFlowName") String integrationFlowName,
            @JsonProperty("Status") String status,
            @JsonProperty("LogStart") String logStart,
            @JsonProperty("LogEnd") String logEnd,
            @JsonProperty("Sender") String sender,
            @JsonProperty("Receiver") String receiver) {
    }

    /** A deployed iFlow. Status: STARTED, STARTING, ERROR, STOPPING. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntegrationRuntimeArtifact(
            @JsonProperty("Id") String id,
            @JsonProperty("Version") String version,
            @JsonProperty("Name") String name,
            @JsonProperty("Type") String type,
            @JsonProperty("DeployedBy") String deployedBy,
            @JsonProperty("DeployedOn") String deployedOn,
            @JsonProperty("Status") String status) {
    }

    private static final Pattern ODATA_DATE = Pattern.compile("/Date\\((-?\\d+)\\)/");

    /** {@code Instant} to OData V2's {@code /Date(millis)/}. */
    public static String toODataDate(Instant instant) {
        return "/Date(" + instant.toEpochMilli() + ")/";
    }

    /** OData V2's {@code /Date(millis)/} to {@code Instant}; null if it is not one. */
    public static Instant fromODataDate(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ODATA_DATE.matcher(value);
        return matcher.matches() ? Instant.ofEpochMilli(Long.parseLong(matcher.group(1))) : null;
    }
}
