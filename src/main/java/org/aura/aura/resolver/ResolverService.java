package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.resilience.AnthropicTransientFailures;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.tools.ToolDefinitions;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class ResolverService {

    // One dependency (Claude), one shared instance name — the retry policy and the breaker policy in
    // application.yml both bind to "anthropicApi", so the config reads as two views of one dependency.
    private static final String CLAUDE = "anthropicApi";

    // The ANSWER-AFFECTING request shape, exposed as the SINGLE SOURCE OF TRUTH for both the request
    // (paramsFor) and the Day 9 cache key (CachedResolutionService → CacheKeyFactory). Deriving the key
    // from these same constants is what makes a code edit here self-invalidating: bump MAX_TOKENS and
    // every previously-truncated cached answer is orphaned by construction (ADR-019). The system prompt
    // is the fourth such input; it comes from the shared ResolverPromptProvider, which both this service
    // (paramsFor) and CachedResolutionService read — one source, so key and request can't drift.
    public static final String MODEL_ID = Model.CLAUDE_SONNET_4_5.asString();
    public static final long MAX_TOKENS = 2048L;
    // Set EXPLICITLY (1.0 is also the API default, so this is a no-op behaviourally) precisely so the
    // cache key can fold in a REAL request parameter rather than a fiction: temperature is
    // answer-affecting, and a silent default would be un-keyable.
    public static final double TEMPERATURE = 1.0;

    private final AnthropicClient client;
    private final ResolverPromptProvider prompts;
    // Injected only so the fallback can report the breaker's actual state (OPEN vs HALF_OPEN vs
    // FORCED_OPEN) in its WARN line — that log becomes the Day 24 "how often do we degrade" metric.
    private final CircuitBreakerRegistry circuitBreakers;
    // Day 17 (ADR-045): what the blocking request advertises. Read here, in the request builder, so the
    // tools and the prompt that describes them are assembled in the one place that builds requests.
    private final ToolDefinitions tools;

    // Day 14: KnowledgeBase is GONE from this constructor. Retrieval no longer happens here — it
    // happens in CachedResolutionService, BEFORE the cache key is computed, because the key now
    // hashes the retrieved bytes (Decision 4). Leaving a retrieve() call in this class as well would
    // mean embedding and searching twice per ticket, and the second result could differ from the one
    // the key was built from.
    public ResolverService (AnthropicClient client, ResolverPromptProvider prompts,
                            CircuitBreakerRegistry circuitBreakers, ToolDefinitions tools){
        this.client = client;
        this.prompts = prompts;
        this.circuitBreakers = circuitBreakers;
        this.tools = tools;
    }

    // Retry + circuit breaker apply here, on a PUBLIC method reached across a bean boundary
    // (ResolverToolLoop calls it once per round). That's mandatory: Spring implements these
    // annotations with an AOP proxy, and a self-invocation (this.ask()) would bypass the proxy and
    // silently disable both policies. ask() is safe to retry — it is one model call with no side
    // effect to duplicate; see the airbag note below for what had to leave this method to keep it so.
    //
    // The fallback degrades to human escalation on the two "Claude is unhealthy" paths — breaker open,
    // or a transient failure whose retries were exhausted — and RE-PROPAGATES everything else. It keys
    // off the SAME transient allowlist as retry/record-exceptions: not on the list (a permanent 400/401,
    // or any unknown/future exception type) means "surface it", never "mask it as an escalation". See
    // escalateToHuman for the fail-closed detail.
    //
    // fallbackMethod sits on @Retry (the OUTER aspect), NOT @CircuitBreaker (inner). Order matters: the
    // aspects nest Retry(CircuitBreaker(call)), so a fallback on the inner breaker would catch the first
    // transient error and return BEFORE @Retry ever retried it — silently disabling retries. On the
    // outer @Retry, the fallback runs only after retries are spent (or a breaker-open rejection has
    // passed straight through), which is exactly when we want to decide "degrade vs surface".
    //
    // Day 14 changed the SIGNATURE, not the policy: the context arrives already retrieved rather than
    // being fetched here. That keeps this method a pure "ask Claude" step — which is what makes it
    // still safe to retry. Had retrieval stayed inside, every retry would have re-embedded the ticket
    // (a billable Voyage call) and re-run the search, so a Claude rate-limit blip would have cost
    // three embeddings, and a retry could have been answered from a DIFFERENT context than the one
    // the cache key was computed over.
    //
    // Day 17 — THE AIRBAG RULE (ADR-047, extending Day 8). What these annotations wrap shrank to ONE
    // MODEL CALL: build the request, send it, classify the reply. The tool loop, the dispatch, and the
    // G3/G4 gates all moved OUT, into ResolverToolLoop, which calls this method once per round across
    // the bean boundary. That is not tidiness. A retried unit must be a pure read; "resolve" stopped
    // being one the moment a round could create a ticket or queue a refund. Had dispatch stayed inside,
    // a 429 on round two would have made Resilience4j replay round one's writes — and, symmetrically,
    // a failing executor would have been counted by the breaker as Anthropic being down.
    //
    // What IS still inside, and why that is fine: G0 parsing. An unreadable end_turn payload is a
    // property of this one response, retrying it re-sends the identical conversation (the
    // ResolverConversation is immutable), and no tool runs in between.
    @Retry(name = CLAUDE, fallbackMethod = "escalateToHuman")
    @CircuitBreaker(name = CLAUDE)
    public ResolverTurn ask(ResolverConversation conversation) {
        StructuredMessage<ResolverOutput> message = client.messages().create(paramsFor(conversation, true));
        logUsage(message.usage());

        // stop_reason BEFORE parsing — the same gate the classifier uses (Day 6): on "refusal" the
        // content is empty and on "max_tokens" the JSON is truncated mid-object, so touching .text()
        // first would surface both as a raw Jackson parse exception with the actual cause lost.
        Optional<StopReason> stopReason = message.stopReason();

        // Day 17: the model is asking us to act. Returned, not executed — this method never dispatches.
        if (stopReason.isPresent() && StopReason.TOOL_USE.equals(stopReason.get())) {
            return toolUseTurn(message);
        }

        // Day 17: our own cap was too small. RETURNED rather than thrown so the breaker records a
        // SUCCESS — the dependency answered correctly; it was our fuse that blew (the G0 posture: a
        // property of one response, never of Anthropic's health). The loop retries once with a raised
        // cap and fails loud if that truncates too.
        if (stopReason.isPresent() && StopReason.MAX_TOKENS.equals(stopReason.get())) {
            return new ResolverTurn.Truncated();
        }

        // Anything else that is not end_turn (refusal, pause_turn, stop_sequence, absent) does NOT
        // degrade to a safe answer. The resolver has no neutral reply to fall back on — inventing one
        // would break the prompt's own "never invent" rule — and ESCALATED_TO_HUMAN is reserved for
        // dependency health, which this is not. So it surfaces: "fail loud on our mistakes, degrade
        // only on the dependency's". IllegalStateException is deliberately absent from the transient
        // allowlist, so escalateToHuman rethrows it rather than masking it as a bogus outage.
        if (stopReason.isEmpty() || !StopReason.END_TURN.equals(stopReason.get())) {
            throw new IllegalStateException("Resolver returned stop_reason="
                    + stopReason.map(StopReason::asString).orElse("<absent>") + " — no usable reply");
        }

        // GATE 0 — the structured output must be READABLE. G3/G4 judge an answer; this judges whether
        // there is one to judge. See parseOrThrow.
        return new ResolverTurn.Answered(parseOrThrow(message));
    }

    /**
     * A {@code tool_use} reply, split into what the loop needs: the assistant message VERBATIM (the
     * API requires it echoed back before the results, and a paraphrase of it would be a different
     * conversation) and every {@code tool_use} block in it.
     *
     * <p>Blocks are selected BY TYPE, never by position. A turn may open with a text block ("Let me
     * check that order…") before its first {@code tool_use}, and may carry several {@code tool_use}
     * blocks — so {@code content().get(0)} would be wrong on both counts, and wrong silently: it would
     * either miss the call or dispatch only the first of two.
     */
    private ResolverTurn toolUseTurn(StructuredMessage<ResolverOutput> message) {
        List<ToolUseBlock> calls = message.content().stream()
                .filter(StructuredContentBlock::isToolUse)
                .map(StructuredContentBlock::asToolUse)
                .toList();
        if (calls.isEmpty()) {
            // stop_reason says "tool_use" and there is no tool_use block: a malformed response, which
            // is G0's territory (retryable, then OUTPUT_UNUSABLE) rather than a bug of ours.
            throw new ResolverOutputUnusableException("tool_use resolver response carried no tool_use block");
        }
        return new ResolverTurn.ToolUse(message.rawMessage().toParam(), calls);
    }

    /**
     * G3 and G4, over an output that has already been parsed — the ONE implementation, reachable from
     * both transports.
     *
     * <h2>Why this is public, and what went wrong while it was not</h2>
     * Day 7's rule for this pair is "ONE prompt, TWO doors", and Day 10 held it for the REQUEST by
     * routing both transports through {@code paramsFor}. Day 16 put all of its new enforcement on the
     * RESPONSE, and there was no equivalent seam there — so the gates were reachable only from
     * {@code resolve}, and {@code /resolve/stream} shipped answers no gate had ever seen. The
     * invariant was never written down as code, so it held exactly as long as nobody added anything
     * to the half it did not cover.
     *
     * <p>Extracting this is what makes the rule structural rather than remembered: a future G5 added
     * here is a G5 both doors get, and one added inline in {@code resolve} would be a compile-time
     * no-op for streaming rather than a silent behavioural gap.
     *
     * <p>Note what is deliberately NOT here: the {@code stop_reason} gate and {@link #parseOrThrow}.
     * Those consume a {@code StructuredMessage}, which only the blocking transport has — the stream
     * delivers its stop_reason on a {@code message_delta} frame and its payload as accumulated text.
     * The caller is responsible for establishing that a readable {@link ResolverOutput} exists; what
     * this method owns is judging it.
     *
     * @param output  a successfully parsed model output — never null
     * @param context the block that produced the request, and therefore the only legitimate source of
     *                citable ids
     */
    public Resolution applyGroundingGates(ResolverOutput output, ContextBlock context) {
        // ── THE GROUNDING GATES ──────────────────────────────────────────────────────────────────
        //
        // The prompt's <grounding> contract is SOFT: it is an instruction, and an instruction is a
        // request the model may decline, misread, or drift away from at temperature 1.0. These two
        // gates are the HARD half. Nothing below trusts prose, and nothing below asks the model a
        // second question — each gate compares what the model returned against something we already
        // know independently.
        //
        // ORDER IS THE DESIGN, and it is cheapest-and-most-decisive first. G3 reads one boolean and
        // can end the request; G4 only runs on answers that survived it, which means the citation
        // check never has to reason about what an empty citation list means on an ungrounded answer.
        // Reverse them and every G4 log line would be full of ungrounded answers that were never
        // going to be shown.

        // G3 — the model's own verdict, taken at face value in the ONE direction it is safe to.
        // A model claiming it is grounded proves nothing (G4 checks that); a model volunteering that
        // it is NOT is the one report it has no incentive to fake, and it is the report the contract
        // explicitly asks for. Refusing is a correct outcome, so this is INFO, not WARN.
        //
        // The answer content is DISCARDED rather than shown, and that is a backstop rather than the
        // normal case: clause (c) tells the model to leave `reply` empty here, so there is usually
        // nothing to discard. It exists for the model that says "I am not grounded" and then answers
        // anyway — which is precisely the failure this gate is for, and precisely the answer that
        // must not reach a customer.
        if (!output.grounded()) {
            log.info("grounding gate G3 — model reported grounded=false against {} excerpt(s); "
                            + "escalating to a human and discarding {} character(s) of answer text",
                    context.sourcesProvided().size(), output.reply() == null ? 0 : output.reply().length());
            return Resolution.escalatedUngrounded(EscalationCause.UNGROUNDED);
        }

        // G4 — the citations must survive being checked against the ledger.
        Optional<List<SourceRef>> cited = validateCitations(output.citations(), context);
        if (cited.isEmpty()) {
            return Resolution.escalatedUngrounded(EscalationCause.UNVERIFIABLE_CITATIONS);
        }

        // sourcesProvided is copied STRAIGHT off the context block — the same object whose rendered
        // bytes were sent — and never read from the model. Note what is deliberately NOT happening
        // here: no filtering by "did the reply mention this chunk", no reconciliation against the
        // model's prose. If the answer names a source, that is narration. This field is the ledger of
        // what was in the request, and a ledger that edits itself to match the story is not a ledger.
        //
        // sourcesCited is the OTHER half, and it is only allowed to exist because it has just been
        // checked: every entry is a SourceRef looked up out of that same ledger, never a string
        // carried over from the model. So the two lists cannot describe different chunks.
        return Resolution.resolved(
                output.reply(),
                context.sourcesProvided(),
                cited.get(),
                output.escalate());
    }

    /**
     * GATE 0 — turn the response into a {@link ResolverOutput}, or into a retryable failure.
     *
     * <p>"The schema was enforced server-side, so a clean end_turn is guaranteed to parse" was true
     * enough to write in a comment on Day 10 and is the kind of guarantee that should not be load-
     * bearing: it depends on a provider's implementation, on the SDK's deserializer, and on the
     * record and the schema staying in step across a refactor. When it does fail, the consequence is
     * specific — grounding cannot be checked on an answer that cannot be read — so the failure gets a
     * type of its own ({@link ResolverOutputUnusableException}) that is retried once by Resilience4j
     * and then degraded to a human, rather than a bare exception that surfaces as a 500.
     *
     * <p>Note what stays ABOVE this method and keeps its old treatment: the {@code stop_reason} gate.
     * A {@code max_tokens} cutoff is our own 2048-token fuse blowing and a {@code refusal} is the
     * model declining — both are decisions someone made, not a response we failed to read, and
     * retrying either just spends money reproducing it. They still fail loud.
     */
    private ResolverOutput parseOrThrow(StructuredMessage<ResolverOutput> message) {
        StructuredTextBlock<ResolverOutput> block = message.content().stream()
                .flatMap(content -> content.text().stream())
                .findFirst()
                .orElseThrow(() -> new ResolverOutputUnusableException(
                        "end_turn resolver response carried no text block"));
        try {
            return block.text();
        } catch (RuntimeException parseFailure) {
            throw new ResolverOutputUnusableException(
                    "end_turn resolver response did not deserialize into ResolverOutput", parseFailure);
        }
    }

    /**
     * GATE 4 — every cited id must name an excerpt this request actually supplied, and there must be
     * at least one.
     *
     * <p><b>Membership is checked against what the model was SHOWN, not against the corpus.</b> The
     * candidate set here is {@code context.sourcesProvided()} — the survivors that were rendered into
     * the {@code <document>} elements — not the k=8 rows the search returned, and not kb_chunks. A
     * real chunk id that was dropped by the token budget is still a fabricated citation for THIS
     * answer: the model never saw its text, so it cannot have drawn a fact from it, and a check
     * against the whole corpus would wave that through as legitimate.
     *
     * <p><b>Empty-with-grounded-true is a violation, not a lenient pass.</b> It is the cheapest way
     * for a model to satisfy a grounding contract without doing any grounding — assert the verdict,
     * skip the evidence — so treating it as "no citations offered, carry on" would leave the gate
     * with nothing to check on exactly the answers most likely to need checking.
     *
     * <p>The returned list is in the LEDGER's canonical order (distance, then id), not the order the
     * model happened to list its ids in. Two reasons: the result is cached, so a field that varies
     * with an incidental model choice would make two identical outcomes look different to anyone
     * diffing them; and it makes {@code sourcesCited} a genuine subsequence of {@code sourcesProvided},
     * which is the relationship the two fields claim to have.
     *
     * @return the resolved subset, or {@link Optional#empty()} when the gate FAILED — the empty
     *         Optional means "escalate", and is deliberately not the same value as a successfully
     *         resolved empty list, which cannot occur because an empty claim is itself a violation
     */
    private Optional<List<SourceRef>> validateCitations(List<String> claimed, ContextBlock context) {
        Map<String, SourceRef> provided = new LinkedHashMap<>();
        for (SourceRef ref : context.sourcesProvided()) {
            provided.put(ref.chunkId().toString(), ref);
        }

        if (claimed.isEmpty()) {
            // WARN, like a foreign id and for the same reason: the model asserted a verdict it
            // offered no evidence for. Day 24 counts these two together as "citation contract
            // violations" and apart as two different ways of breaking it.
            log.warn("grounding gate G4 — model reported grounded=true but cited NOTHING; escalating. "
                            + "{} excerpt(s) were available: {}",
                    provided.size(), provided.keySet());
            return Optional.empty();
        }

        List<String> foreign = claimed.stream().filter(id -> !provided.containsKey(id)).toList();
        if (!foreign.isEmpty()) {
            // THE MODEL-MISBEHAVIOUR SIGNAL, and the offending ids are named rather than counted.
            // A count tells an operator that something is wrong; the ids tell them WHICH kind —
            // an "example-chunk-1" is the few-shot leaking into a real answer, a well-formed uuid
            // that is not in the provided set is the model reaching for a chunk it saw in an earlier
            // turn or inventing one wholesale. Those want different fixes, and the difference is
            // invisible without the value.
            log.warn("grounding gate G4 — model cited {} id(s) that were NOT in this request's "
                            + "excerpts; escalating and discarding the answer. foreign={} provided={}",
                    foreign.size(), foreign, provided.keySet());
            return Optional.empty();
        }

        Set<String> claimedIds = Set.copyOf(claimed);   // also collapses a duplicated citation
        return Optional.of(context.sourcesProvided().stream()
                .filter(ref -> claimedIds.contains(ref.chunkId().toString()))
                .toList());
    }

    // Circuit-breaker fallback. Two degrade paths — logged distinctly so Day 24 can count them as
    // separate metrics — plus a fail-closed rethrow for everything else:
    //
    //   1. CallNotPermittedException — the breaker is OPEN: Claude has failed enough that we've stopped
    //      calling it. A sustained outage. ("degraded: breaker open")
    //   2. A transient transport failure whose retries were exhausted — a single-request blip that
    //      didn't clear within the attempt budget. ("degraded: retries exhausted") The allowlist here
    //      is the SAME transient taxonomy as retry-exceptions / record-exceptions in application.yml.
    //
    // Both return a real, business-valid Resolution (HTTP 200, Day 5) rather than a 5xx — a human is a
    // better outcome for the customer than an error page.
    //
    // ALLOWLIST, not a denylist: we enumerate what degrades; anything not matched — a permanent
    // 400/401/404, or any unknown/future exception type — is RETHROWN and surfaces honestly. Note there
    // is deliberately NO "isPermanent(statusCode)" check: absence from the transient list IS the whole
    // decision, exactly like the retry allowlist. A denylist would fail OPEN, silently masking an
    // unrecognised error as a bogus outage escalation.
    //
    // The parameter list MIRRORS ask()'s, plus the Throwable — that is how Resilience4j finds a
    // fallback, and it is a match it makes reflectively at runtime. So adding a parameter to
    // ask() and forgetting it here would not fail to compile; it would fail at the first
    // transient error, at which point the degrade path silently stops existing and a rate limit
    // becomes a 500. ResolverResilienceTest exercises this path for exactly that reason.
    //
    // Day 17: it returns a ResolverTurn.Degraded rather than a Resolution, because the unit it guards
    // is now one model call. The loop turns that into the escalation — carrying any tools that already
    // ran in earlier rounds, which is what keeps a round-2 outage after a round-1 lookup out of Redis.
    @SuppressWarnings("unused") // invoked reflectively by the Resilience4j @CircuitBreaker aspect
    private ResolverTurn escalateToHuman(ResolverConversation conversation, Throwable cause) throws Throwable {
        if (cause instanceof CallNotPermittedException) {
            var state = circuitBreakers.circuitBreaker(CLAUDE).getState();
            log.warn("Claude unavailable — circuit breaker '{}' is {}; escalating ticket to a human. cause={}",
                    CLAUDE, state, cause.toString());
            return escalated();
        }
        if (cause instanceof RateLimitException
                || cause instanceof InternalServerException
                || cause instanceof AnthropicIoException) {
            log.warn("Claude transient failure, retries exhausted; escalating ticket to a human. cause={}",
                    cause.toString());
            return escalated();
        }
        // Day 11: a hung response (client timeout mid-read) surfaces as AnthropicInvalidDataException
        // caused by a timeout, NOT AnthropicIoException. That is a dependency hang — fail fast to a
        // human, not a 5xx. A malformed body (same type, non-timeout cause) is excluded and still
        // rethrows below. NOT retried (see AnthropicTransientFailures): retrying a hang only stacks
        // timeouts before the same escalation.
        if (AnthropicTransientFailures.isReadTimeout(cause)) {
            log.warn("Claude response read timed out; escalating ticket to a human. cause={}",
                    cause.toString());
            return escalated();
        }
        // Day 16, GATE 0's landing point. The dependency was HEALTHY here — it answered, quickly,
        // with a 200 — and the answer could not be read after every retry. That is a third distinct
        // degrade cause and it gets its own log line for the same reason the two above do: Day 24
        // counts them separately, and "how often does Claude return something we cannot parse" is a
        // different operational question from "how often is Claude down".
        //
        // It is also the ONE place this method degrades on something that is not the dependency's
        // fault, which reads like a breach of the Day 8 law until the alternative is written down.
        // Failing loud here does not surface a bug for someone to fix — the response is gone, there
        // is nothing to inspect — it just turns an unreadable answer into a 500. A human agent is
        // strictly better for the customer, and the WARN is what makes it visible to us.
        if (cause instanceof ResolverOutputUnusableException) {
            log.warn("Claude returned an unreadable structured output and retries are exhausted; "
                    + "escalating ticket to a human. cause={}", cause.toString());
            return new ResolverTurn.Degraded(EscalationCause.OUTPUT_UNUSABLE);
        }
        throw cause;
    }

    // The loop maps this onto the shared factory (Resolution.escalatedToHuman, Day 14):
    // CachedResolutionService's retrieval catch produces the same degraded answer for a different
    // unhealthy dependency, and the customer must not be able to tell the two apart from the wording.
    private ResolverTurn escalated() {
        return new ResolverTurn.Degraded(EscalationCause.DEPENDENCY_UNAVAILABLE);
    }

    // ADR-020: prompt-cache observability. cacheReadInputTokens > 0 means the static system-prompt
    // prefix was served from Anthropic's 5-min ephemeral cache (a hit, ~90% cheaper on that prefix);
    // cacheCreationInputTokens > 0 means this call WROTE the prefix (a ~25% surcharge that the next
    // call within 5 min recoups). Both SDK fields are Optional — absent on an uncached call — so
    // .orElse(0L). This is best-effort telemetry: a resolution we already paid for and obtained must
    // never fail because usage couldn't be read, so a missing usage block degrades to "no log", never
    // an error (same fail-open spirit as the Day 9 cache).
    private void logUsage(Usage usage) {
        if (usage == null) return;
        log.info("resolver usage — inputTokens={}, cacheCreationInputTokens={}, cacheReadInputTokens={}",
                usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
    }

    // Streaming (Day 7) shares the EXACT retrieve-augment step as the blocking path above, so a
    // streamed answer is grounded identically to a non-streamed one — only the transport (block
    // vs stream) differs. The streaming caller takes these params and opens createStreaming()
    // instead of create(); sources aren't returned here because the streaming contract surfaces
    // usage/stop_reason rather than the KB receipt (Day 9 will persist the full turn).
    //
    // Day 10: rawParams() unwraps the typed params back to the plain MessageCreateParams that
    // createStreaming takes. The output_config is injected into the request body at build() time, so
    // it travels WITH the unwrapped params — the stream stays schema-enforced, and what arrives on
    // the wire is JSON, not prose. That is exactly why the pump can no longer forward text deltas
    // straight to the customer and needs StreamingReplyExtractor to unwrap the envelope.
    //
    // ResolverServiceTest asserts output_config is actually present on what this returns. That test
    // is not ceremony: if rawParams() ever dropped it, the request would silently revert to prose,
    // the extractor would match no "reply" key, and every SSE customer would get an EMPTY stream —
    // a silent total failure with no exception anywhere to catch it.
    //
    // Day 14: the context is a PARAMETER now, for the same reason it is on resolve(). Retrieving here
    // would have given the streaming transport its own private retrieval — a second call site,
    // separately maintained, free to drift from the blocking one in k, in budget, or in dedup. The
    // whole "ONE prompt, TWO doors" invariant below only holds if both doors are handed the same
    // shape of input, so the caller retrieves and both transports render identically.
    //
    // Day 17: the stream is TOOL-FREE — the one deliberate difference between the two doors. The SSE
    // pump buffers one text envelope behind the gates (Day 16 Decision 4); it has no way to pause
    // mid-stream, dispatch, and resume, so advertising tools there would let the model emit a
    // tool_use nobody answers. Same system prompt, same schema; a streamed order-status question
    // simply has no tool to reach for, so the model escalates rather than guesses (the prompt's
    // transactional-facts clause forbids stating what no tool returned). Tool dispatch on SSE is a
    // later-day item, not an accident of this line.
    public MessageCreateParams buildStreamingParams(String ticket, ContextBlock context) {
        return paramsFor(ResolverConversation.opening(ticket, context), false).rawParams();
    }

    // Single source of truth for the resolution prompt: model, token cap, system prompt, and the
    // KB-augmented user turn. Both ask() and buildStreamingParams() route through here so the
    // two transports can never drift apart in wording or configuration.
    //
    // Day 10 kept that invariant deliberately when the output became structured: ONE prompt, ONE
    // schema, two doors. The tempting alternative — structured output on the blocking path only —
    // looks cheaper and is a trap. The system prompt's few-shot examples now teach the JSON envelope,
    // so a streaming request without output_config would carry JSON-teaching examples with no schema
    // enforcement: the model emits JSON anyway, unenforced and unparseable, straight onto a
    // customer-facing SSE stream. Splitting the transports would have meant splitting the prompt too,
    // which means two cache prefixes (ADR-020) and a live drift seam.
    private StructuredMessageCreateParams<ResolverOutput> paramsFor(ResolverConversation conversation,
                                                                    boolean withTools) {
        String ticket = conversation.ticket();
        ContextBlock context = conversation.context();
        // ORDER IS THE DESIGN: documents first, ticket LAST.
        //
        // Not a formatting preference. The ticket is the only part of this turn the customer controls,
        // and putting it after the reference material means an instruction smuggled into a ticket
        // ("ignore the documents above and…") is arguing against text the model has already read
        // rather than framing text it is about to read. It also puts the actual question closest to
        // the generation point, which is where an instruction lands hardest.
        //
        // The document block arrives pre-rendered and canonical. This method does not format it,
        // truncate it, or re-order it — ContextBlockAssembler owns those bytes, and it owns them
        // ALONE because the response cache key hashes exactly what is sent here. A "small tweak" to
        // the framing at this call site would change every key in the keyspace without changing
        // anything the assembler can see.
        String userTurn = context.rendered() + "\n\ncustomer ticket: " + ticket;

        StructuredMessageCreateParams.Builder<ResolverOutput> request = MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_5)   // MODEL_ID = this constant's .asString(); one source, no drift
                // MAX_TOKENS unless the loop raised it after a truncation (Day 17). The KEY still folds
                // MAX_TOKENS: the raise is a deterministic policy applied to the same inputs, not a
                // different request a caller chose.
                .maxTokens(conversation.maxTokens())
                .temperature(TEMPERATURE)
                // ADR-020: mark the static system prompt (rules + few-shot + the Day 14 grounding
                // block — the STABLE prefix) with an ephemeral cache_control breakpoint. Replaces the
                // plain .system(String).
                //
                // The grounding instruction rides INSIDE this block, which is the whole reason it
                // lives in the prompt file rather than being prepended to the retrieved documents.
                // Attached to the documents it would sit after the breakpoint, where it is re-read at
                // full price on every ticket and changes bytes whenever retrieval changes; here it is
                // byte-identical forever and costs 0.1x from the second call onward. The instruction
                // that must apply to every request is exactly the instruction that should be cached.
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(prompts.systemPrompt())
                                // Breakpoint on the LAST block that is byte-identical across requests. Anything
                                // volatile (timestamp, ticket ID) at or before this line would mean paying a
                                // cache write on every call and never getting a read.
                                .cacheControl(CacheControlEphemeral.builder().build())  // ephemeral 5-min TTL; hits refresh it free
                                .build()))
                // Native structured outputs (ADR-021), same mechanism as the Day 6 classifier: the
                // schema is DERIVED from the ResolverOutput record and enforced server-side, so
                // `escalate` arrives as a real boolean instead of something we'd have to infer from
                // prose. This call is what re-types the builder to StructuredMessageCreateParams.
                //
                // Placed AFTER the cache_control breakpoint line for readability only — output_config
                // is a request-body field, not a content block, so it sits outside the cached prefix
                // and its position in this chain has no effect on what gets cached.
                .outputConfig(ResolverOutput.class)
                // The ticket goes in messages — AFTER the breakpoint, never cached.
                .addUserMessage(userTurn);

        // Day 17: the tool-loop turns, appended AFTER the opening user turn and in order — each
        // assistant message verbatim, then the one user message holding all of its tool results.
        conversation.followUps().forEach(request::addMessage);

        // Day 17 (ADR-045): tools render at the very start of the cache prefix (tools → system →
        // messages), so they are a PREFIX input. ToolDefinitions hands them over sorted by name with
        // ordered schema maps, so these bytes are identical on every request and the ADR-020 prefix
        // keeps hitting.
        if (withTools) {
            request.tools(tools.toolUnions());
        }
        return request.build();
    }
}
