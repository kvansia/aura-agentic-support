package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Shared, stateless builders for the Day 17 tool tests. Named neither {@code *Test} nor {@code *IT} so
 * neither Surefire nor Failsafe runs it.
 */
public final class ToolFixtures {

    private ToolFixtures() {}

    /** The fixed clock every fake is seeded from in tests: "yesterday" is always 2026-09-22. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);

    private static final ObjectMapper JSON = new ObjectMapper();

    public static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("fixture is not JSON: " + raw, e);
        }
    }
}
