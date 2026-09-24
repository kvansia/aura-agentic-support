package org.aura.aura.resolver;

import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.client.VoyagePermanentException;
import org.aura.aura.client.VoyageTransientException;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.RetrievalService;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.tools.ToolDefinitions;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cache-aside policy for {@link CachedResolutionService} (ADR-018 + Day 14's Decision 4). Each test
 * states one sentence of that policy; the LLM is never touched — the resolver itself is mocked at the
 * bean boundary.
 */
@ExtendWith(MockitoExtension.class)
class CachedResolutionServiceTest {

    private static final String TICKET = "How long do I have to return something?";
    private static final ResolveTicketRequest REQUEST = new ResolveTicketRequest(TICKET);
    private static final String KEY = "aura:resolution:v2:deadbeef";

    private static final ContextBlock CONTEXT = new ContextBlock(
            "<documents>\n<document id=\"x\" breadcrumb=\"Refund Policy\">30 days</document>\n</documents>",
            List.of(new SourceRef(UUID.randomUUID(), "Refund Policy", 0.19)));

    @Mock RetrievalService retrieval;
    @Mock CacheKeyFactory keys;
    @Mock ResolutionCache cache;
    @Mock ResolverToolLoop resolver;
    @Mock ResolverPromptProvider prompts;
    @Mock ToolDefinitions tools;

    @InjectMocks CachedResolutionService service;

    // POLICY (Decision 4): retrieval happens BEFORE the key is computed, because the retrieved bytes
    // are one of the key's inputs. This ordering is the entire day's cache work — get it backwards and
    // the key is blind to KB edits again, which is the defect the reposition exists to fix.
    @Test
    void retrievesBeforeComputingTheKey() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(resolution("cached", ResolutionStatus.RESOLVED)));

        service.resolve(REQUEST);

        InOrder order = inOrder(retrieval, keys, cache);
        order.verify(retrieval).retrieve(TICKET);
        order.verify(keys).resolutionKey(any(), any(), anyInt(), any(), any(), any(), anyDouble(), anyLong());
        order.verify(cache).get(KEY);
    }

    // POLICY: the RETRIEVED BYTES are what gets keyed. Not the chunk ids, not a count, not the query
    // vector — the rendered block, verbatim. This is what makes a KB edit self-invalidating.
    @Test
    void keysOnTheRenderedContextBlock() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(resolution("cached", ResolutionStatus.RESOLVED)));

        service.resolve(REQUEST);

        verify(keys).resolutionKey(eq(ResolverService.MODEL_ID), any(), anyInt(), any(),
                eq(CONTEXT.rendered()), eq(TICKET), eq(ResolverService.TEMPERATURE),
                eq(ResolverService.MAX_TOKENS));
    }

    // POLICY: a cache HIT returns the stored resolution and never calls the resolver — the whole point,
    // and the outage guarantee (OPEN breaker + hit => real answer) rides on the resolver being untouched.
    //
    // Day 14 narrowed what "free" means here: a hit still pays retrieval (one Voyage query embedding
    // plus one pgvector search) before it can discover it is a hit. What it still skips is the
    // expensive call — Sonnet — which is where the money was.
    @Test
    void returnsCachedResolutionWithoutCallingResolver() {
        Resolution cached = resolution("cached answer", ResolutionStatus.RESOLVED);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(cached));

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(resolver);
    }

    // POLICY: a MISS calls the resolver exactly once and stores the fresh, non-degraded answer.
    @Test
    void callsResolverOnceAndStoresOnMiss() {
        Resolution fresh = resolution("fresh answer", ResolutionStatus.RESOLVED);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(fresh);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(fresh);
        verify(resolver, times(1)).resolve(TICKET, CONTEXT);
        verify(cache).put(KEY, fresh);
    }

    // POLICY: the resolver is handed the SAME context object the key was computed from. Retrieving a
    // second time inside the resolver would not merely waste a billable embedding — the corpus is
    // live and embeddings are not bit-reproducible, so the second result could differ, and the entry
    // would then be stored under a key describing documents the model never saw.
    @Test
    void sendsTheResolverExactlyTheContextThatWasKeyed() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(resolution("fresh", ResolutionStatus.RESOLVED));

        service.resolve(REQUEST);

        verify(retrieval, times(1)).retrieve(TICKET);   // exactly once for the whole request
        verify(resolver).resolve(TICKET, CONTEXT);
    }

    // POLICY: an ADR-014 escalation fallback is an availability answer, not knowledge — it is returned
    // to the caller but NEVER cached, or every ticket would keep escalating for the whole TTL after
    // Anthropic recovers.
    @Test
    void fallbackResolutionIsNeverCached() {
        Resolution escalated = Resolution.escalatedToHuman();
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(escalated);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(escalated);
        verify(cache, never()).put(any(), any());
    }

    // POLICY (Day 10): a MODEL-CHOSEN escalation is still a knowledge answer and IS cached. This is
    // the distinction the two escalation channels exist to preserve — the gate keys off `status`
    // (dependency health), never off `escalate` (business judgment). Cache-skipping every escalation
    // would mean paying Sonnet again on every repeat of the tickets most likely to repeat, and the
    // same ticket deserves the same escalation tomorrow anyway.
    @Test
    void modelChosenEscalationIsCachedBecauseItIsAKnowledgeAnswer() {
        Resolution escalating = Resolution.resolved(
                "I'm escalating this to a specialist.", List.of(), List.of(), true);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(escalating);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(escalating);
        verify(cache).put(KEY, escalating);
    }

    // ---------------------------------------------------------------- Day 16: grounding vs the cache

    // POLICY: a GROUNDING refusal IS cached, and this is the one Day 16 behaviour change here.
    //
    // The old gate was "never store an ESCALATED_TO_HUMAN result", which was correct while the
    // Resilience4j fallback was that status's only writer. "The knowledge base does not answer this
    // question" is a different kind of fact: it is about the ticket and the corpus rather than about
    // this minute, it will be just as true in an hour, and re-deriving it costs a full Sonnet call on
    // exactly the tickets most likely to be asked again.
    //
    // It cannot fossilize, and not because anyone remembers to flush it: the key hashes the RETRIEVED
    // BYTES (Decision 4), so publishing the missing policy document and re-ingesting changes what this
    // ticket retrieves, changes its key, and orphans the cached refusal.
    @Test
    void aGroundingRefusalIsCachedBecauseItIsAKnowledgeAnswer() {
        Resolution refused = Resolution.escalatedUngrounded(EscalationCause.UNGROUNDED);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(refused);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(refused);
        verify(cache).put(KEY, refused);
    }

    @Test
    void aCitationViolationRefusalIsCachedForTheSameReason() {
        Resolution refused = Resolution.escalatedUngrounded(EscalationCause.UNVERIFIABLE_CITATIONS);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(refused);

        service.resolve(REQUEST);

        verify(cache).put(KEY, refused);
    }

    // POLICY: an UNREADABLE-OUTPUT escalation is NOT cached, even though no dependency was down.
    // "Was a dependency unhealthy" is the wrong question for the cache; "would repeating this request
    // legitimately give a different answer" is the right one, and here it would — the model answered
    // three times and produced garbage three times at temperature 1.0, which is a property of those
    // draws and not of the ticket. Caching it would freeze one bad generation in front of a question
    // the model will very likely answer correctly next time.
    @Test
    void anUnreadableOutputEscalationIsNotCachedEvenThoughNothingWasDown() {
        Resolution unusable = Resolution.escalatedToHuman(EscalationCause.OUTPUT_UNUSABLE);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(unusable);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(unusable);
        verify(cache, never()).put(any(), any());
    }

    // ---------------------------------------------------------------- Decision 5: retrieval degrades

    // POLICY: a retrieval dependency that is unwell degrades to a human, exactly as an unwell Claude
    // does. Before this, the same customer got a 200 + handoff when Anthropic was rate-limited and a
    // 500 when Voyage was — one outage, two experiences, decided by which service happened to break.
    @Test
    void aVoyageFailureWithRetriesSpentEscalatesInsteadOfPropagating() {
        when(retrieval.retrieve(TICKET))
                .thenThrow(new VoyageTransientException("Voyage transient failure: HTTP 429 on voyage-4-lite"));

        Resolution result = service.resolve(REQUEST);

        assertThat(result.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        // BOTH channels, so a caller reading only `escalate` still routes correctly during an outage.
        assertThat(result.escalate()).isTrue();
        // No documents reached the model, so the receipt is honestly empty rather than listing the
        // context this request never obtained.
        assertThat(result.sourcesProvided()).isEmpty();
    }

    @Test
    void aRetrievalFailureNeverReachesTheResolverAndNeverWritesToRedis() {
        when(retrieval.retrieve(TICKET)).thenThrow(new VoyageTransientException("429"));

        service.resolve(REQUEST);

        // The resolver is untouched: there is no context to ask about, and paying Sonnet to answer
        // from nothing would be worse than escalating.
        verifyNoInteractions(resolver);
        // Nothing is written — and nothing is READ either. Both are structural rather than
        // conditional: the key is a hash OF the retrieved bytes, so when retrieval fails there is no
        // key. A future edit cannot invert a conditional that does not exist.
        verifyNoInteractions(cache);
        verifyNoInteractions(keys);
    }

    @Test
    void anUnreachableDatabaseAlsoEscalates() {
        // Postgres is the other retrieval dependency, and "the store did not answer" is the same
        // class of event as "the embedding provider did not answer".
        when(retrieval.retrieve(TICKET))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThat(service.resolve(REQUEST).status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
    }

    // POLICY: fail-closed. Absence from the allowlist IS the decision, exactly as in the resolver's
    // fallback. A 401 from Voyage is a bad API key — OUR misconfiguration — and masking it as an
    // outage would let a broken deployment escalate every ticket to a human while looking healthy.
    @Test
    void aPermanentVoyageFailureSurfacesRatherThanMasqueradingAsAnOutage() {
        when(retrieval.retrieve(TICKET))
                .thenThrow(new VoyagePermanentException("Voyage permanent failure: HTTP 401"));

        assertThatThrownBy(() -> service.resolve(REQUEST))
                .isInstanceOf(VoyagePermanentException.class)
                .hasMessageContaining("401");

        verifyNoInteractions(resolver);
    }

    @Test
    void anUnknownRetrievalFailureSurfacesUnwrapped() {
        // The catch has to be broad to observe anything at all; the DECISION is made on type. An
        // unrecognised failure must leave exactly as it arrived, not wrapped in something that hides
        // its type from a caller or a log scraper.
        IllegalStateException ours = new IllegalStateException("a bug of ours");
        when(retrieval.retrieve(TICKET)).thenThrow(ours);

        assertThatThrownBy(() -> service.resolve(REQUEST)).isSameAs(ours);
    }

    // ---------------------------------------------------------------- Day 17: tools in the key

    // POLICY: the advertised tools are keyed as part of the static prefix, AHEAD of the system prompt
    // (the order the API renders them) — so a tool edit orphans keys exactly like a prompt edit.
    @Test
    void theToolAdvertisementIsKeyedAheadOfTheSystemPrompt() {
        when(tools.canonicalForm()).thenReturn("[TOOLS]");
        when(prompts.systemPrompt()).thenReturn("SYSTEM");
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(resolution("cached", ResolutionStatus.RESOLVED)));

        service.resolve(REQUEST);

        verify(keys).resolutionKey(any(), any(), anyInt(), eq("[TOOLS]SYSTEM"), any(), any(),
                anyDouble(), anyLong());
    }

    // ---------------------------------------------------------------- fixtures

    private void stubKey() {
        when(keys.resolutionKey(any(), any(), anyInt(), any(), any(), any(), anyDouble(), anyLong()))
                .thenReturn(KEY);
    }

    private static Resolution resolution(String answer, ResolutionStatus status) {
        return status == ResolutionStatus.RESOLVED
                ? Resolution.resolved(answer, CONTEXT.sourcesProvided(), CONTEXT.sourcesProvided(), false)
                : Resolution.escalatedToHuman();
    }
}
