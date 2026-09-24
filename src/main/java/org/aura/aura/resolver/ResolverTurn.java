package org.aura.aura.resolver;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.List;

/**
 * The outcome of ONE resolver model call — the unit {@code @Retry}/{@code @CircuitBreaker} wrap
 * (Day 17: the airbag rule, see {@link ResolverToolLoop}).
 *
 * <p>A sealed result rather than "a message, or an exception" because the loop has to branch on four
 * genuinely different things, and only one of them is a failure of anything:
 * <ul>
 *   <li>{@link Answered} — {@code end_turn}, and the payload parsed (G0 passed). Ready for G3/G4.</li>
 *   <li>{@link ToolUse} — {@code tool_use}: the model is asking us to act. Carries the assistant
 *       message VERBATIM (it must be echoed back before the results) and the blocks to dispatch.</li>
 *   <li>{@link Truncated} — {@code max_tokens}: our own cap was too small for this answer. A RETURN,
 *       not an exception, precisely so the circuit breaker records a success: the dependency is fine.</li>
 *   <li>{@link Degraded} — the Resilience4j fallback's answer: the dependency was unhealthy, or its
 *       output was unreadable after every retry. Carries the cause for the cache and for Day 24.</li>
 * </ul>
 */
public sealed interface ResolverTurn {

    record Answered(ResolverOutput output) implements ResolverTurn {}

    record ToolUse(MessageParam assistant, List<ToolUseBlock> calls) implements ResolverTurn {
        public ToolUse {
            calls = List.copyOf(calls);
        }
    }

    record Truncated() implements ResolverTurn {}

    record Degraded(EscalationCause cause) implements ResolverTurn {}
}
