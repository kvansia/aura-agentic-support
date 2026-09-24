package org.aura.aura.resolver;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.tools.ToolRegistry;
import org.aura.aura.tools.ToolResultPayload;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 17 — the resolver, with hands. Wraps {@link ResolverService#ask} in the Messages API tool-use
 * protocol: the model may REQUEST actions; only this class, through {@link ToolRegistry}, EXECUTES them.
 *
 * <h2>Where this sits</h2>
 * {@code CachedResolutionService} → <b>this</b> → {@code ResolverService.ask} (the resilience-wrapped
 * single call) → Anthropic. The Redis response cache is consulted ONCE, above this class, before the
 * first round (the Day 9 path, unchanged); nothing in here reads the cache, so every mid-loop call goes
 * to the model.
 *
 * <h2>THE AIRBAG RULE (ADR-047 / Day 8)</h2>
 * Resilience4j wraps ONE model call and nothing else. Dispatch happens HERE, outside any retried or
 * breaker-recorded unit, for two symmetric reasons:
 * <ul>
 *   <li>A retry must never re-run a write. If round 2's model call 429s, Resilience4j retries round 2's
 *       call — with the SAME immutable {@link ResolverConversation} — not round 1's dispatch. A ticket
 *       created in round 1 is created once.</li>
 *   <li>The breaker measures Anthropic. An executor fault is not Anthropic being down, so it never
 *       reaches the breaker: executors never throw, and a fault becomes an {@code is_error} tool_result
 *       the model can explain or escalate on.</li>
 * </ul>
 *
 * <h2>What leaves this class</h2>
 * The final {@code end_turn} answer goes through G3/G4 ({@link ResolverService#applyGroundingGates})
 * UNCHANGED — tool use adds no bypass. Every outcome, including every escalation, is stamped with the
 * tools that ran, and {@link Resolution#toolTouched()} is what keeps any of it out of Redis.
 */
@Slf4j
@Service
public class ResolverToolLoop {

    /**
     * Tool rounds per ticket. A round = one assistant {@code tool_use} turn dispatched and answered, so
     * the model is called at most MAX_TOOL_ROUNDS + 1 times (plus at most one truncation re-ask). Three
     * covers "look up the order, act on it, confirm" with one to spare; past that the model is usually
     * re-querying in a circle. A named constant today; promoted to a policy object on Day 18.
     */
    static final int MAX_TOOL_ROUNDS = 3;

    /**
     * The cap for the ONE re-ask after a {@code max_tokens} stop: double the 2048 fuse. A legitimate
     * answer that brushed the cap fits; a runaway generation truncates again and fails loud.
     */
    static final long RAISED_MAX_TOKENS = ResolverService.MAX_TOKENS * 2;

    // The PROXIED bean — calling across the boundary is what keeps @Retry/@CircuitBreaker live.
    private final ResolverService resolver;
    private final ToolRegistry tools;

    public ResolverToolLoop(ResolverService resolver, ToolRegistry tools) {
        this.resolver = resolver;
        this.tools = tools;
    }

    public Resolution resolve(String ticket, ContextBlock context) {
        List<String> toolsInvoked = new ArrayList<>();

        Asked asked = askOnce(ResolverConversation.opening(ticket, context));
        int round = 0;
        while (asked.turn() instanceof ResolverTurn.ToolUse toolUse && round < MAX_TOOL_ROUNDS) {
            // The assistant message goes back VERBATIM and FIRST, then exactly ONE user message holding
            // every result. Splitting results across user messages, or answering only some blocks, is a
            // protocol error — and results answer blocks by tool_use_id, never by position.
            MessageParam results = dispatchAll(toolUse.calls(), toolsInvoked);
            round++;
            asked = askOnce(asked.conversation().append(toolUse.assistant(), results));
        }

        Resolution outcome = switch (asked.turn()) {
            // G3/G4 UNCHANGED: a tool-assisted answer earns no exemption from the grounding gates.
            case ResolverTurn.Answered answered -> resolver.applyGroundingGates(answered.output(), context);
            case ResolverTurn.Degraded degraded -> Resolution.escalatedToHuman(degraded.cause());
            case ResolverTurn.ToolUse stillAsking -> {
                // INCIDENT-SHAPED, and logged like one: WARN, naming what the model kept asking for.
                log.warn("tool loop exhausted {} round(s) with the model still requesting {}; escalating "
                                + "ticket to a human. invoked={}",
                        MAX_TOOL_ROUNDS, stillAsking.calls().stream().map(ToolUseBlock::name).toList(),
                        toolsInvoked);
                yield Resolution.escalatedToHuman(EscalationCause.TOOL_ROUNDS_EXHAUSTED);
            }
            case ResolverTurn.Truncated truncated ->
                    throw new IllegalStateException("unreachable: askOnce resolves every truncation");
        };
        return outcome.withToolsInvoked(toolsInvoked);
    }

    /**
     * One model call, plus the single {@code max_tokens} re-ask (G0-style: a property of this response,
     * never of Anthropic's health — which is why {@code ask} RETURNS {@code Truncated} instead of
     * throwing, and the breaker records a success).
     *
     * <p>A raised cap is carried forward in the returned conversation, so a later round does not
     * re-discover the same truncation at 2048. A second truncation — at the raised cap, or in a later
     * round after the raise — fails loud as it did before Day 17: our fuse is wrong for this ticket, and
     * that is a bug to see, not an outage to mask.
     */
    private Asked askOnce(ResolverConversation conversation) {
        ResolverTurn turn = resolver.ask(conversation);
        if (!(turn instanceof ResolverTurn.Truncated)) {
            return new Asked(turn, conversation);
        }
        if (conversation.maxTokens() < RAISED_MAX_TOKENS) {
            log.info("resolver hit max_tokens={}; re-asking once with max_tokens={}",
                    conversation.maxTokens(), RAISED_MAX_TOKENS);
            ResolverConversation raised = conversation.withMaxTokens(RAISED_MAX_TOKENS);
            turn = resolver.ask(raised);
            if (!(turn instanceof ResolverTurn.Truncated)) {
                return new Asked(turn, raised);
            }
        }
        throw new IllegalStateException("Resolver returned stop_reason=max_tokens at max_tokens="
                + RAISED_MAX_TOKENS + " — no usable reply");
    }

    /**
     * Dispatches EVERY {@code tool_use} block of one assistant turn and answers them all in ONE user
     * message — tool_result blocks only, so they are necessarily first in its content array (the API
     * rejects a tool-result message with anything ahead of the results).
     *
     * <p>Sequential, in the order the model emitted them. "Parallel tool use" is a protocol property —
     * several calls in one turn — not a threading requirement; running a write concurrently with the
     * read it may depend on would buy nothing here and cost determinism.
     */
    private MessageParam dispatchAll(List<ToolUseBlock> calls, List<String> toolsInvoked) {
        List<ContentBlockParam> results = new ArrayList<>(calls.size());
        for (ToolUseBlock call : calls) {
            ToolResultPayload payload = tools.dispatch(call.name(), call.id(), inputOf(call));
            toolsInvoked.add(call.name());
            log.info("tool dispatched — name={}, tool_use_id={}, is_error={}", call.name(), call.id(),
                    payload.isError());
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())           // matched by id — the only link the API honours
                    .content(payload.content())
                    // is_error describes EXECUTION, not outcome: true only for faults (see ToolResultPayload)
                    .isError(payload.isError())
                    .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build();
    }

    /** The model's raw input as a tree, or null when it is not even JSON — which executors reject as invalid. */
    private static JsonNode inputOf(ToolUseBlock call) {
        try {
            return call._input().convert(JsonNode.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A turn together with the conversation that produced it — its cap may have been raised on the way. */
    private record Asked(ResolverTurn turn, ResolverConversation conversation) {}
}
