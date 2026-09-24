package org.aura.aura.resolver;

/**
 * WHY a resolution ended in {@link ResolutionStatus#ESCALATED_TO_HUMAN}.
 *
 * <h2>Why this is a new channel instead of more constants on ResolutionStatus</h2>
 * {@link ResolutionStatus}'s own javadoc predicted that Day 16 would "grow the escalation taxonomy"
 * and pointed at itself as the seam. It grew somewhere else, and the reason is worth recording: that
 * enum's {@code name()} is on the wire, as {@code ResolutionResponse.outcome}. Adding
 * {@code ESCALATED_UNGROUNDED} beside {@code ESCALATED_TO_HUMAN} would have made every integrator's
 * {@code outcome == "ESCALATED_TO_HUMAN"} check silently wrong the first time the knowledge base was
 * silent on a question — a client that routed escalations to a queue would quietly stop routing some
 * of them. So the OUTCOME stays one value (a human is taking this ticket) and the DIAGNOSIS moves
 * here, where widening it is additive by construction.
 *
 * <h2>What actually depends on this distinction</h2>
 * Two things, and neither could be done off {@code status} alone:
 *
 * <ul>
 *   <li><b>The cache</b> (ADR-018). An escalation caused by an unhealthy dependency must never be
 *       stored — Anthropic recovers and we would keep escalating for the rest of the TTL. An
 *       escalation caused by a knowledge gap is the opposite: it is the RIGHT answer for this ticket
 *       against this corpus, it will be the right answer tomorrow, and re-deriving it costs a Sonnet
 *       call. Same status, opposite cache policy. See {@link Resolution#isIncidentalOutcome()}.</li>
 *   <li><b>The eval harness.</b> {@code EvalScorer} excludes a degraded resolver stage from scoring,
 *       because a dependency outage grades nothing about judgment. A grounding refusal grades a great
 *       deal about judgment — it is the entire point of the Day 16 golden-set slices — so it must
 *       stay scored. Keying that exclusion on {@code status} would have made every correct refusal
 *       invisible to the eval on the very day refusals started being measured.</li>
 * </ul>
 */
public enum EscalationCause {

    /** Not an escalation. The pairing with {@link ResolutionStatus#RESOLVED} is the only valid one. */
    NONE,

    /**
     * A dependency was unwell: Claude's breaker was open or its retries were spent (Day 8), or
     * retrieval's Voyage/Postgres leg failed (Day 14, Decision 5). No model judgment took place.
     */
    DEPENDENCY_UNAVAILABLE,

    /**
     * G3 — the model answered and reported {@code grounded=false}: the excerpts it was given do not
     * contain what the customer needs. A correct outcome, not a failure. The reply it wrote (if any)
     * is discarded rather than shown.
     */
    UNGROUNDED,

    /**
     * G4 — the model claimed {@code grounded=true} but its citations do not hold up: the list was
     * empty, or it named an id that was not among the excerpts this request supplied. Unlike
     * {@link #UNGROUNDED} this is MODEL MISBEHAVIOUR rather than a knowledge gap, and it is logged at
     * WARN with the offending ids for exactly that reason — it is the telemetry seam Day 24 counts.
     */
    UNVERIFIABLE_CITATIONS,

    /**
     * G0 — the response arrived but no {@link ResolverOutput} could be got out of it, after the
     * retry budget was spent. Grounding cannot be verified on an answer that cannot be read, and an
     * unverifiable answer must not reach a customer.
     */
    OUTPUT_UNUSABLE,

    /**
     * Day 17 — the model was still asking for tools when the loop's round budget
     * ({@code ResolverToolLoop.MAX_TOOL_ROUNDS}) ran out. INCIDENT-SHAPED: it describes how one
     * conversation went (a model stuck re-querying, a tool answering something it could not use), not
     * a fact about the ticket, so it is never cached — the next attempt may well finish in one round.
     */
    TOOL_ROUNDS_EXHAUSTED;

    /**
     * True when this escalation is a property of THIS ONE CALL rather than of the ticket and the
     * corpus — so repeating the request could legitimately produce a different outcome.
     *
     * <p>That is the question the cache actually needs answered, and it is not the same question as
     * "was a dependency down". {@link #OUTPUT_UNUSABLE} happens while the dependency is perfectly
     * healthy — the API answered, the answer was garbage — and caching it would freeze one bad
     * generation in front of a question the model would very likely answer correctly on the next
     * attempt. {@link #UNGROUNDED} and {@link #UNVERIFIABLE_CITATIONS} are decided by content the key
     * already covers (the prompt, the ticket, the retrieved bytes), so they repeat.
     */
    public boolean isIncidental() {
        return this == DEPENDENCY_UNAVAILABLE || this == OUTPUT_UNUSABLE || this == TOOL_ROUNDS_EXHAUSTED;
    }
}
