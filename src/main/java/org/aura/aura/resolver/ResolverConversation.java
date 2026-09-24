package org.aura.aura.resolver;

import com.anthropic.models.messages.MessageParam;
import org.aura.aura.retrieval.ContextBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything one resolver model call is built from: the Day 14 inputs (ticket + the already-retrieved
 * context) plus, from Day 17, the tool-loop turns appended after the first user message.
 *
 * <p>Immutable, and that is load-bearing: the SAME instance is handed to every Resilience4j attempt of
 * one call, so a retry re-sends exactly the conversation the failed attempt sent — never one a
 * half-finished dispatch has since appended to.
 *
 * @param followUps assistant/user pairs after the opening user turn, in order: each assistant message
 *                  verbatim, then the one user message carrying all of its tool results
 * @param maxTokens the cap for THIS call — {@link ResolverService#MAX_TOKENS} unless a truncation
 *                  raised it
 */
public record ResolverConversation(String ticket, ContextBlock context, List<MessageParam> followUps,
                                   long maxTokens) {

    public ResolverConversation {
        followUps = List.copyOf(followUps);
    }

    public static ResolverConversation opening(String ticket, ContextBlock context) {
        return new ResolverConversation(ticket, context, List.of(), ResolverService.MAX_TOKENS);
    }

    public ResolverConversation withMaxTokens(long cap) {
        return new ResolverConversation(ticket, context, followUps, cap);
    }

    public ResolverConversation append(MessageParam assistant, MessageParam toolResults) {
        List<MessageParam> next = new ArrayList<>(followUps);
        next.add(assistant);
        next.add(toolResults);
        return new ResolverConversation(ticket, context, next, maxTokens);
    }
}
