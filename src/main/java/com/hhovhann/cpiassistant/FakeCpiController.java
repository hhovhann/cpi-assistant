package com.hhovhann.cpiassistant;

import com.hhovhann.cpiassistant.CpiODataModel.IntegrationRuntimeArtifact;
import com.hhovhann.cpiassistant.CpiODataModel.MessageProcessingLog;
import com.hhovhann.cpiassistant.CpiODataModel.ODataList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in CPI tenant, until a real one is reachable: the same paths and
 * JSON as the real OData API, served from {@link FakeCpiData}. On by default
 * ({@code cpi.tenant.fake=true}); with a real tenant, turn it off and point
 * {@code cpi.tenant.base-url} there.
 * <p>
 * It understands only the OData the client sends: {@code $filter} with
 * {@code eq} / {@code gt} joined by {@code and}, {@code $orderby=LogEnd desc}
 * and {@code $top}. Anything else in a filter is rejected, not ignored — a
 * silently ignored condition would return wrong data that looks right.
 */
@RestController
@RequestMapping("/fake-cpi/api/v1")
@ConditionalOnProperty(name = "cpi.tenant.fake", havingValue = "true", matchIfMissing = true)
public class FakeCpiController {

    private static final Pattern CONDITION =
            Pattern.compile("(\\w+) (eq|gt) (?:'((?:[^']|'')*)'|datetime'([^']*)')");

    private final FakeCpiData data;

    public FakeCpiController() {
        this(Clock.systemUTC());
    }

    FakeCpiController(Clock clock) {
        this.data = new FakeCpiData(clock);
    }

    @GetMapping(path = "/MessageProcessingLogs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ODataList<MessageProcessingLog> messageProcessingLogs(
            @RequestParam(name = "$filter", required = false) String filter,
            @RequestParam(name = "$top", defaultValue = "100") int top) {
        Predicate<MessageProcessingLog> matches = parseFilter(filter);
        return ODataList.of(data.logs().stream()
                .filter(matches)
                .sorted(Comparator.comparing((MessageProcessingLog log) -> CpiODataModel.fromODataDate(log.logEnd())).reversed())
                .limit(top)
                .toList());
    }

    @GetMapping(path = "/MessageProcessingLogs('{guid}')/ErrorInformation/$value", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> errorInformation(@PathVariable String guid) {
        String error = data.error(guid);
        return error == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(error);
    }

    @GetMapping(path = "/IntegrationRuntimeArtifacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ODataList<IntegrationRuntimeArtifact> integrationRuntimeArtifacts() {
        return ODataList.of(data.artifacts());
    }

    static Predicate<MessageProcessingLog> parseFilter(String filter) {
        Predicate<MessageProcessingLog> all = log -> true;
        if (filter == null || filter.isBlank()) {
            return all;
        }
        for (String condition : filter.split(" and ")) {
            Matcher m = CONDITION.matcher(condition.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("Fake tenant cannot filter by: " + condition);
            }
            String field = m.group(1);
            String operator = m.group(2);
            String text = m.group(3) == null ? null : m.group(3).replace("''", "'");
            Predicate<MessageProcessingLog> matches = switch (field + " " + operator) {
                case "Status eq" -> log -> log.status().equals(text);
                case "IntegrationFlowName eq" -> log -> log.integrationFlowName().equals(text);
                case "LogEnd gt" -> {
                    Instant after = LocalDateTime.parse(m.group(4)).toInstant(ZoneOffset.UTC);
                    yield log -> CpiODataModel.fromODataDate(log.logEnd()).isAfter(after);
                }
                default -> throw new IllegalArgumentException("Fake tenant cannot filter by: " + condition);
            };
            all = all.and(matches);
        }
        return all;
    }
}
