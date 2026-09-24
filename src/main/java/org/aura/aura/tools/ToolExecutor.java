package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Executes ONE advertised tool on the model's behalf (ADR-047: dispatch is a registry of these, keyed
 * by {@link #toolName()}, checked against {@link ToolDefinitions} at boot — see {@link ToolRegistry}).
 *
 * <h2>The contract: never throw</h2>
 * Every fault maps to {@link ToolResultPayload#error(String)}. The call site is the resolver's tool
 * loop, which sits OUTSIDE the Resilience4j unit on purpose (the airbag rule — see
 * {@code ResolverToolLoop}); an exception escaping here would either 500 the ticket or, worse, find its
 * way into retry machinery that would re-run a write. An error result instead goes back to the model as
 * an {@code is_error} tool_result, which it can explain to the customer or escalate on.
 *
 * <h2>The input is untrusted</h2>
 * The model is an in-process client, and it is not ours. Its {@code input} is validated against the
 * advertised schema inside the executor, BEFORE any service call ({@link ToolInput}); an invalid input
 * is {@code error("invalid_input")} and never reaches a service.
 */
public interface ToolExecutor {

    /** The advertised name this executor answers for. Must match exactly one {@link ToolDefinitions} entry. */
    String toolName();

    /**
     * @param toolUseId the {@code tool_use} block's id. Threaded through (the brief's sketch took only
     *                  the input) because it is the idempotency key for the write tools: a dispatch-layer
     *                  replay of the same block must not create a second ticket or a second pending
     *                  refund. Read-only tools ignore it.
     * @param input     the model's raw {@code input} object — unvalidated
     */
    ToolResultPayload execute(String toolUseId, JsonNode input);
}
