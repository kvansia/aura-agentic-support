package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What one tool execution hands back to the model: the {@code tool_result} content as a compact JSON
 * string, plus the {@code is_error} flag.
 *
 * <h2>is_error describes EXECUTION, not OUTCOME</h2>
 * "No such order" is a perfectly successful execution with an unwelcome answer, so it is
 * {@code ok({"found":false,"reason":"no_such_order"})} — a fact the model should relay. {@code is_error}
 * is reserved for "the tool could not do its job": invalid input, an unknown tool, a backing service
 * that fell over. Conflating the two teaches the model that a missing order is a system fault worth
 * retrying, and teaches operators to page on customers typing the wrong number.
 *
 * <h2>Allowlisted bodies only (ADR-047)</h2>
 * The body is built field-by-field by each executor from typed values — never by serializing a domain
 * object whole, and never carrying customer or model free text back. Whatever goes in here is read by
 * the model as part of its next turn, so every string field is an injection surface; the only strings
 * allowed are ids, enum values, dates, and carrier codes we control.
 */
public record ToolResultPayload(String content, boolean isError) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static ToolResultPayload ok(JsonNode body) {
        return new ToolResultPayload(compact(body), false);
    }

    /** @param code a short machine-readable reason ("invalid_input", "unknown_tool", ...) — no prose, no echo */
    public static ToolResultPayload error(String code) {
        ObjectNode body = JSON.createObjectNode();
        body.put("error", code);
        return new ToolResultPayload(compact(body), true);
    }

    /** A fresh node for executors to fill with allowlisted fields. */
    public static ObjectNode body() {
        return JSON.createObjectNode();
    }

    private static String compact(JsonNode body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            // A JsonNode tree always serializes, so this is unreachable — but the contract is still
            // "never throw", so it degrades to a fault result rather than propagating.
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
