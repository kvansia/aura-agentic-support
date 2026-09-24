package org.aura.aura.resolver;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.client.VoyageTransientException;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.RetrievalService;
import org.aura.aura.tools.ToolDefinitions;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import java.util.Optional;

// ADR-018: cache-aside in front of the resolver. The prompt's "TicketResolutionService" is AURA's
// ResolverService; "TicketRequest" is ResolveTicketRequest; the cached "ResolutionResponse" is the
// domain Resolution.
@Slf4j
@Service
public class CachedResolutionService {

    private final RetrievalService retrieval;
    private final CacheKeyFactory keys;
    private final ResolutionCache cache;
    // Injected Spring bean => the call below crosses the AOP proxy, so Day 8's
    // @Retry/@CircuitBreaker stay live. If this logic sat INSIDE the resolver class and
    // used a plain `this.` call, the proxy would be bypassed and the resilience stack
    // would silently vanish while every client-mocking test still passed (Day 8 pitfall,
    // now made structurally impossible).
    //
    // Day 17: this is the TOOL LOOP now, not ResolverService directly. The loop calls the proxied
    // ResolverService once per round, so the same property holds one layer further down.
    private final ResolverToolLoop resolver;
    // The static system prompt (one of the answer-affecting key inputs) is read HERE from the
    // shared provider, NOT from `resolver` — deliberately, so a cache HIT never touches the resolver
    // bean (that's what lets the outage path "OPEN + hit => real answer" hold, and what
    // verifyNoInteractions(resolver) asserts). Same provider ResolverService.paramsFor uses, so the
    // keyed prefix is byte-identical to the one that carries the cache_control breakpoint.
    private final ResolverPromptProvider prompts;
    // Day 17: the advertised tools are part of the same static prefix as the system prompt (they render
    // AHEAD of it), so they are keyed alongside it — see ToolDefinitions.canonicalForm().
    private final ToolDefinitions tools;

    public CachedResolutionService(RetrievalService retrieval, CacheKeyFactory keys,
                                   ResolutionCache cache, ResolverToolLoop resolver,
                                   ResolverPromptProvider prompts, ToolDefinitions tools) {
        this.retrieval = retrieval;
        this.keys = keys;
        this.cache = cache;
        this.resolver = resolver;
        this.prompts = prompts;
        this.tools = tools;
    }

    public Resolution resolve(ResolveTicketRequest request) {
        String ticketText = request.message();

        // RETRIEVE FIRST — the Day 14 reordering, and the single most consequential line in this
        // method (Decision 4). The key cannot be computed before this call, because the retrieved
        // document bytes are one of its inputs; see CacheKeyFactory for why keying on anything less
        // means a KB edit keeps serving pre-edit answers for a full TTL.
        //
        // WHAT THIS COSTS, stated rather than buried: a cache HIT is no longer free. It now pays one
        // Voyage query embedding and one pgvector search before it can even discover that it is a
        // hit. That is the price of self-invalidation and it is worth it — the expensive call is
        // Sonnet, and a hit still skips that entirely — but the "hit costs nothing" property from
        // Day 9 is gone and nobody should be surprised by it later.
        //
        // WHAT THIS ALSO CHANGES: retrieval sits OUTSIDE the Resilience4j stack, which only wraps
        // resolver.resolve — so an unhealthy Voyage or Postgres would surface as a 5xx rather than as
        // the ADR-014 human escalation. Decision 5 closes that, in degradeOnRetrievalFailure below.
        ContextBlock context;
        try {
            context = retrieval.retrieve(ticketText);
        } catch (Exception failure) {
            return degradeOnRetrievalFailure(failure);
        }

        String key = keys.resolutionKey(
                ResolverService.MODEL_ID,
                prompts.promptId(),
                prompts.promptVersion(),
                // The STATIC PREFIX, in the order the API renders it: tools, then system. Folded into the
                // existing slot rather than a new parameter because it is one input — "everything ahead
                // of the per-request turn" — and a tool edit must orphan keys exactly like a prompt edit.
                tools.canonicalForm() + prompts.systemPrompt(),
                context.rendered(),
                ticketText,
                ResolverService.TEMPERATURE,
                ResolverService.MAX_TOKENS);

        // ORDER IS THE DESIGN (ADR-018): the cache is checked BEFORE the resilience
        // stack. A hit is not a call to Anthropic — it must not occupy a slot in the
        // breaker's sliding window, and it must not be BLOCKED when the breaker is OPEN.
        // Outage behavior: OPEN + hit => real answer; OPEN + miss => ADR-014 escalation.
        // The cache is an availability layer wearing a cost costume.
        Optional<Resolution> hit = cache.get(key);
        if (hit.isPresent()) return hit.get();

        // The SAME context object that produced the key is what gets sent. Retrieving a second time
        // inside the resolver would not merely waste a billable embedding — it could return a
        // different result (the corpus is live, and embeddings are not bit-reproducible), and then
        // the entry would be stored under a key describing documents the model never saw.
        // What comes back is the FINAL, POST-GATE outcome — never the raw model output. The grounding
        // gates run inside resolve(), so a rejected answer has already been replaced by an escalation
        // before this line sees it, and there is no code path on which an ungrounded reply or an
        // unverified citation could be written to Redis and served to someone else tomorrow. That is
        // a property of WHERE the gates live, not of a check here.
        Resolution fresh = resolver.resolve(ticketText, context);

        // WHAT IS CACHED, and the Day 16 change: escalations ARE cached now — the grounding ones.
        //
        // The gate used to be "never store an ESCALATED_TO_HUMAN result", which was right when the
        // Resilience4j fallback was that status's only writer: an availability answer cached is a
        // ticket that keeps escalating for a full TTL after Anthropic recovers. The grounding gates
        // added two more writers with the opposite property. "The knowledge base does not answer this
        // question" is not a fact about today's weather — it is a fact about this ticket and this
        // corpus, it will be just as true in an hour, and re-deriving it costs a full Sonnet call on
        // exactly the tickets most likely to be asked again.
        //
        // WHY THAT CANNOT FOSSILIZE, which is the obvious objection: the key hashes the RETRIEVED
        // BYTES (Decision 4). Publishing the missing policy document and re-ingesting changes what
        // this ticket retrieves, which changes its key, which orphans the cached refusal — no TTL to
        // wait out and no flush to remember. The invalidation that makes caching a refusal safe is
        // the same mechanism that already makes caching an ANSWER safe; it is not a new promise.
        //
        // isIncidentalOutcome() is what keeps the two apart: it is true for a dependency failure and
        // for an unreadable response (both properties of one call), false for a grounding refusal (a
        // property of the question). See EscalationCause.
        if (!fresh.isIncidentalOutcome()) {
            cache.put(key, fresh);
        }
        return fresh;
    }

    /**
     * DECISION 5 — retrieval is a dependency too, so it degrades like one.
     *
     * <p>Day 8 established the law for Claude: degrade on the DEPENDENCY's problems, fail loud on
     * OURS. Retrieval added two more dependencies to the request path — Voyage and Postgres — and
     * until now they had no such treatment, so a rate-limited embedding call turned a ticket into a
     * 500 while the identical failure one layer down turned it into a human handoff at HTTP 200. Same
     * customer, same kind of outage, two different experiences, decided by which service happened to
     * be unhealthy. A human agent is a better outcome than an error page in both cases.
     *
     * <h2>ALLOWLIST, not a denylist — the same taxonomy, one layer up</h2>
     * Only failures that mean "a dependency is unwell" degrade:
     *
     * <ul>
     *   <li>{@link VoyageTransientException} — 429/5xx/socket timeout with the retry budget spent.
     *       The embedding provider is having a bad minute.</li>
     *   <li>{@link DataAccessResourceFailureException} — Postgres unreachable. Spring files
     *       "cannot get a connection" under NonTransient, which is a naming quirk rather than a
     *       judgement: from here it is exactly "the store did not answer".</li>
     *   <li>{@link QueryTimeoutException} — it answered, too slowly.</li>
     * </ul>
     *
     * <p>Everything else RETHROWS, and the omissions are deliberate. A
     * {@code VoyagePermanentException} is a 400/401/422 — a bad API key or a malformed request, which
     * is OUR bug, and masking it as an outage would let a misconfigured deployment escalate every
     * single ticket to a human while reporting itself healthy. A {@code BadSqlGrammarException} is
     * likewise ours. Absence from the list IS the decision, exactly as in
     * {@code ResolverService.escalateToHuman}: unknown fails closed, and closed here means "surface
     * it".
     *
     * <h2>Nothing is written to Redis on this path, and not because we remembered</h2>
     * The key is a hash OF the retrieved bytes (Decision 4), so when retrieval fails there is no key
     * to write under. The cache is not skipped by a conditional that a later edit could invert — it
     * is unreachable. That is a stronger guarantee than the {@code isIncidentalOutcome} gate above,
     * and it is worth having, because caching an availability answer would keep escalating tickets
     * for a full TTL after the dependency recovered.
     */
    private Resolution degradeOnRetrievalFailure(Exception failure) {
        boolean dependencyUnhealthy = failure instanceof VoyageTransientException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof QueryTimeoutException;

        if (!dependencyUnhealthy) {
            // Rethrow unwrapped. The catch had to be broad to see anything at all; the DECISION is
            // made here, on type, and an unrecognised failure leaves exactly as it arrived.
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("retrieval failed", failure);
        }

        // The meter. One line, one distinct message, deliberately shaped like the resolver's two
        // degrade logs so Day 24 can count all three as separate causes of the same customer outcome
        // — "how often do we degrade, and which dependency did it".
        log.warn("retrieval unavailable — escalating ticket to a human. dependency={}, cause={}",
                failure instanceof VoyageTransientException ? "voyage" : "postgres",
                failure.toString(), failure);

        return Resolution.escalatedToHuman();
    }
}
