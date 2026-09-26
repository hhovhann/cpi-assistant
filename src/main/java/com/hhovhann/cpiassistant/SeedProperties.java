package com.hhovhann.cpiassistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The pages saved at startup, bound from {@code cpi.knowledge.*}. A record,
 * not {@code @Value}: {@code @Value} cannot read a YAML list and silently
 * binds it as empty.
 *
 * @param seedOnStartup false in the tests, so they need no internet
 * @param seedPages     page ids from sap-help/catalog.tsv
 */
@ConfigurationProperties("cpi.knowledge")
public record SeedProperties(
        @DefaultValue("true") boolean seedOnStartup,
        @DefaultValue List<String> seedPages) {
}
