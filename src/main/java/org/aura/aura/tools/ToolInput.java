package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hand-rolled validation of a tool's {@code input} against its advertised schema (ADR-047).
 *
 * <p>Hand-rolled, not a JSON-Schema library, because there are three tools with a handful of
 * constraint kinds between them (closed object, required, string pattern, enum, maxLength, integer
 * minimum). A dependency to evaluate that is more surface than the rules themselves. Revisit at five or
 * more tools, or the first time a schema needs a keyword this class does not have.
 *
 * <p>Every check fails with {@link Invalid}, which executors catch and turn into
 * {@code error("invalid_input")}. It never leaves an executor — it is a control-flow device for "stop
 * before the service call", not part of any public contract.
 */
final class ToolInput {

    static final Pattern ORDER_ID = Pattern.compile(ToolDefinitions.ORDER_ID_PATTERN);

    private final JsonNode node;

    private ToolInput(JsonNode node) {
        this.node = node;
    }

    /** The object must exist and carry NO keys outside {@code allowed} — additionalProperties=false. */
    static ToolInput closedObject(JsonNode input, Set<String> allowed) {
        if (input == null || !input.isObject()) {
            throw new Invalid("input is not a JSON object");
        }
        for (Iterator<String> names = input.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new Invalid("unexpected property '" + name + "'");
            }
        }
        return new ToolInput(input);
    }

    String requiredString(String field, Pattern pattern) {
        return optionalString(field, pattern).orElseThrow(() -> new Invalid("missing '" + field + "'"));
    }

    Optional<String> optionalString(String field, Pattern pattern) {
        JsonNode value = node.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new Invalid("'" + field + "' is not a string");
        }
        if (pattern != null && !pattern.matcher(value.textValue()).matches()) {
            throw new Invalid("'" + field + "' does not match " + pattern.pattern());
        }
        return Optional.of(value.textValue());
    }

    String requiredEnum(String field, List<String> allowed) {
        String value = requiredString(field, null);
        if (!allowed.contains(value)) {
            throw new Invalid("'" + field + "' is not one of " + allowed);
        }
        return value;
    }

    String requiredBoundedString(String field, int maxLength) {
        String value = requiredString(field, null);
        // Code points, not UTF-16 units: JSON Schema's maxLength counts characters, and a summary must
        // not be rejected for being "long" in a unit the schema never mentioned.
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new Invalid("'" + field + "' exceeds maxLength " + maxLength);
        }
        return value;
    }

    long requiredInteger(String field, long minimum) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new Invalid("missing '" + field + "'");
        }
        // isIntegralNumber, not isNumber: 49.99 is not a count of cents, and silently truncating it to
        // 49 would move a different amount of money than the model asked for.
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new Invalid("'" + field + "' is not an integer");
        }
        if (value.longValue() < minimum) {
            throw new Invalid("'" + field + "' is below minimum " + minimum);
        }
        return value.longValue();
    }

    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);   // control flow, not a fault: no stack trace
        }
    }
}
