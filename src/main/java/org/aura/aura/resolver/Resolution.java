package org.aura.aura.resolver;

import org.aura.aura.retrieval.SourceRef;

import java.util.List;

// THREE CHANNELS, deliberately kept apart (full rationale on ResolutionStatus and EscalationCause):
//   status          -> WHAT happened: a normal answer, or a human is taking this ticket.
//   escalationCause -> WHY it went to a human. NONE on a RESOLVED answer.
//   escalate        -> the AGENT'S BUSINESS JUDGMENT, copied straight from ResolverOutput.
// A healthy call that decides a human is needed is (RESOLVED, NONE, escalate=true); an outage is
// (ESCALATED_TO_HUMAN, DEPENDENCY_UNAVAILABLE, true); a knowledge gap is (ESCALATED_TO_HUMAN,
// UNGROUNDED, true). Collapsing any two of these into one field is precisely the one-value-many-
// meanings defect Day 10 removed and Day 16 would have reintroduced: grading escalation off `status`
// could only ever have "passed" when the Anthropic API was down.
//
// TWO SOURCE LISTS, and the pair is the whole grounding story:
//   sourcesProvided -> the LEDGER. What was put in front of the model. Ours, derived by
//                      ContextBlockAssembler from the surviving chunk set, never read from the reply.
//   sourcesCited    -> what the model SAID it used, after G4 checked every id against the ledger.
//                      A strict subset of sourcesProvided, in the ledger's canonical order.
//
// Day 14 renamed sourcesUsed -> sourcesProvided because "used" was a claim about what the model DID
// with the context, which nothing here could observe. Day 16 does not undo that rename — it earns the
// missing half honestly. sourcesCited is still the model's claim; the difference is that it is now
// CHECKED before it is stored, so an entry means "the model named this chunk AND this chunk was
// really in the request". Anything that failed the check never became a Resolution at all.
public record Resolution(String answer,
                         List<SourceRef> sourcesProvided,
                         List<SourceRef> sourcesCited,
                         ResolutionStatus status,
                         EscalationCause escalationCause,
                         boolean escalate,
                         List<String> toolsInvoked) {

    /**
     * Null-tolerant, and this is about REDIS rather than about callers.
     *
     * <p>Day 9 caches this record as JSON with a 24h TTL, and Day 16 widened it — so for up to a day
     * after deploy, {@code cache.get} will read entries written by the four-component version, where
     * {@code sourcesCited} and {@code escalationCause} are simply absent. Normalising them here means
     * those entries deserialize into something coherent instead of arriving with nulls that surface
     * as an NPE two layers up.
     *
     * <p>The defaults are the honest reading of an old entry rather than a convenience: a pre-Day-16
     * answer genuinely had no citations, and — because the old cache gate refused to store any
     * {@code ESCALATED_TO_HUMAN} result — every entry that can still be read back is a RESOLVED one,
     * for which {@code NONE} is exactly right. So no old entry can deserialize into the contradiction
     * of an escalation with no cause.
     *
     * <p>This is why {@code CacheKeyFactory}'s KEY_VERSION does NOT move for Day 16. A bump would
     * orphan the entire live keyspace to avoid a shape change the reader can absorb correctly, and
     * the cost of that is a day of full-price Sonnet calls for a problem that does not exist.
     */
    public Resolution {
        sourcesProvided = sourcesProvided == null ? List.of() : List.copyOf(sourcesProvided);
        sourcesCited = sourcesCited == null ? List.of() : List.copyOf(sourcesCited);
        escalationCause = escalationCause == null ? EscalationCause.NONE : escalationCause;
        // Day 17, the same null-tolerance for the same reason: pre-Day-17 cache entries have no
        // toolsInvoked, and "no tools ran" is exactly the honest reading of them — a tool-touched
        // resolution is never written to Redis (ADR-047), so every readable entry is a tool-free one.
        toolsInvoked = toolsInvoked == null ? List.of() : List.copyOf(toolsInvoked);
    }

    /**
     * Day 17 — did any tool execute while producing this outcome? (ADR-047)
     *
     * <p>The cache gate reads this, and it is deliberately a second question beside
     * {@link #isIncidentalOutcome()} rather than folded into it. A tool-touched answer is not
     * incidental — "SF-4412 shipped yesterday" may be perfectly correct — it is UNKEYABLE: the key
     * hashes the prompt, the ticket and the retrieved bytes, and none of those captures the order
     * system's state or whose order it is. Caching it would serve a stale status after the parcel
     * moves, and would replay one customer's order facts to anyone who typed the same sentence.
     *
     * <p>Not named {@code isX}/{@code getX} on purpose: Jackson would otherwise serialize it into the
     * cached JSON as a phantom property.
     */
    public boolean toolTouched() {
        return !toolsInvoked.isEmpty();
    }

    /** The same outcome, stamped with the tools that ran (in dispatch order, repeats kept). */
    public Resolution withToolsInvoked(List<String> tools) {
        return new Resolution(answer, sourcesProvided, sourcesCited, status, escalationCause, escalate, tools);
    }

    /**
     * ADR-018 cache gate, re-keyed for Day 16.
     *
     * <p>It used to ask "is this an ESCALATED_TO_HUMAN result?", which was the same question as "was
     * a dependency down" only because the fallback was the single writer of that status. Day 16 adds
     * two more writers — the grounding gates — and they produce the opposite cache policy: a refusal
     * because the knowledge base is silent is a KNOWLEDGE answer about this ticket and this corpus,
     * it will be the same refusal tomorrow, and re-deriving it costs a Sonnet call. So the question
     * moves to the cause, which is the field that actually knows.
     *
     * <p>Renamed rather than quietly re-keyed. {@code isEscalatedFallback} would still have compiled
     * and would now mean something narrower than it says, which is the failure mode the Day 14
     * sourcesUsed rename exists to warn about.
     */
    public boolean isIncidentalOutcome() {
        return escalationCause.isIncidental();
    }

    /**
     * A grounded, cited answer. The one path on which a customer reads text the model wrote.
     *
     * @param sourcesCited the G4-validated subset of {@code sourcesProvided} — never the raw strings
     *                     off the model, which is what makes an entry here evidence rather than a
     *                     claim
     */
    public static Resolution resolved(String answer, List<SourceRef> sourcesProvided,
                                      List<SourceRef> sourcesCited, boolean escalate) {
        return new Resolution(answer, sourcesProvided, sourcesCited,
                ResolutionStatus.RESOLVED, EscalationCause.NONE, escalate, List.of());
    }

    /**
     * The degraded answer for an unhealthy DEPENDENCY, in the ONE wording AURA is allowed to use.
     *
     * <p>A static factory rather than each degrade path building its own, because as of Day 16 there
     * are three of them — the resolver's Resilience4j fallback (Claude unhealthy),
     * {@code CachedResolutionService}'s retrieval catch (Voyage or Postgres unhealthy, Decision 5),
     * and an unusable model response — and a customer must not be able to tell which one fired from
     * the wording of the apology. Three hand-written strings would drift on the first edit, and the
     * drift would be invisible: all three would still read fine in isolation.
     *
     * <p>Both source lists are EMPTY here, always. These answers were produced INSTEAD of an answer,
     * not from any document — attaching a grounding receipt to one would be claiming evidence for
     * text that has none.
     */
    public static Resolution escalatedToHuman() {
        return escalatedToHuman(EscalationCause.DEPENDENCY_UNAVAILABLE);
    }

    /** @param cause which incidental failure produced it — the customer-visible text is identical. */
    public static Resolution escalatedToHuman(EscalationCause cause) {
        return new Resolution(
                "We couldn't answer this automatically right now, so your ticket has been escalated to a human agent.",
                List.of(),
                List.of(),
                ResolutionStatus.ESCALATED_TO_HUMAN,
                cause,
                // BOTH channels true, and that is not redundancy. `status` records that a human is
                // taking over; `escalate` records WHAT the caller must now do (route to a human), so
                // downstream code reading only `escalate` still behaves correctly during an outage.
                true,
                List.of());
    }

    /**
     * The G3/G4 outcome: the model's answer could not be shown, so a human gets the ticket.
     *
     * <h2>Why the wording differs from the availability apology above</h2>
     * The paragraph above insists a customer must not be able to tell WHICH DEPENDENCY failed, and
     * this is not an exception to that rule — it is a different fact. "Right now" is a promise that
     * waiting helps, and for a question the knowledge base simply does not answer, waiting does not
     * help: the same ticket gets the same result in an hour. Reusing the outage wording here would be
     * telling the customer to come back later for an answer that is never going to appear.
     *
     * <p>Both lists are empty for the same reason as above, and it matters more here: retrieval
     * SUCCEEDED on this path and a full ledger exists. Publishing it would attach a grounding receipt
     * to text that was written by neither the model nor those documents.
     */
    public static Resolution escalatedUngrounded(EscalationCause cause) {
        return new Resolution(
                "I don't have a documented answer for that in ShopFast's knowledge base, so I've "
                        + "escalated your ticket to a human agent who can look into it properly.",
                List.of(),
                List.of(),
                ResolutionStatus.ESCALATED_TO_HUMAN,
                cause,
                true,
                List.of());
    }
}
// Day 6 extends this (category/urgency/intent). Day 24 extends it (tokens/cost/model).
// Day 8 added `status`: the resolve path can now end in a degraded ESCALATED_TO_HUMAN outcome
// (circuit breaker open) that a caller must be able to tell apart from a normal RESOLVED answer.
// Day 10 added `escalate`: the model's own escalation verdict, which until now existed only as
// prose inside the reply text and so could not be measured, routed, or asserted on.
// Day 14 widened the source list from List<String> to List<SourceRef>: an id alone could not carry
// the distance, and a citation with no distance cannot tell a confident answer apart from a
// desperate one — cosine distance is RELATIVE, so retrieval always returns a "best" match.
// Day 16 added `sourcesCited` and `escalationCause`: the first because "what was shown" and "what was
// used" are two facts and the system now knows both; the second because ESCALATED_TO_HUMAN acquired
// three writers with two different cache policies, and one value meaning three things is the defect
// every rename above was undoing.
// Day 17 added `toolsInvoked`: the audit of which tools executed on the way to this outcome, and the
// signal the cache gate needs — a tool-touched resolution is never cached (ADR-047).
