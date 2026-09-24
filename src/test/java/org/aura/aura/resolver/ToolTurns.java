package org.aura.aura.resolver;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StructuredMessage;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Real, wire-shaped resolver responses for the Day 17 loop tests. Parsed from JSON by the SDK's OWN
 * mapper rather than mocked, because the loop echoes {@code rawMessage().toParam()} back verbatim — a
 * mock would make that echo a null and the protocol assertions meaningless.
 *
 * <p>Named neither {@code *Test} nor {@code *IT}, so neither Surefire nor Failsafe runs it.
 */
final class ToolTurns {

    private ToolTurns() {}

    static String text(String text) {
        return "{\"type\":\"text\",\"text\":" + quote(text) + "}";
    }

    static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    /** A {@code stop_reason=tool_use} turn carrying the given content blocks, in order. */
    static StructuredMessage<ResolverOutput> toolUseTurn(String... blocks) {
        return message("tool_use", blocks);
    }

    /** A clean {@code end_turn} whose single text block is the structured envelope. */
    static StructuredMessage<ResolverOutput> endTurn(String envelopeJson) {
        return message("end_turn", text(envelopeJson));
    }

    static StructuredMessage<ResolverOutput> maxTokens() {
        return message("max_tokens", text("{\"reply\":\"cut off mid-"));
    }

    static StructuredMessage<ResolverOutput> message(String stopReason, String... blocks) {
        String json = "{\"id\":\"msg_loop\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-5\",\"content\":"
                + Arrays.stream(blocks).collect(Collectors.joining(",", "[", "]"))
                + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        try {
            return new StructuredMessage<>(ResolverOutput.class,
                    ObjectMappers.jsonMapper().readValue(json, Message.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("fixture is not a Message: " + json, e);
        }
    }

    private static String quote(String raw) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
