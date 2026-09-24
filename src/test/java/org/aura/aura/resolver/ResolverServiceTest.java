package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.ContextBlockAssembler;
import org.aura.aura.retrieval.RetrievedChunk;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.tools.ToolDefinitions;
import org.aura.aura.tools.ToolFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResolverService}'s wiring: what it sends, what it copies from the model, and
 * what it derives itself.
 *
 * <p>The Claude call is stubbed at the API-response seam, so these exercise OUR logic rather than the
 * model's wording — deterministic, and needing neither an API key nor the network. Content blocks are
 * real SDK objects wrapping raw JSON, so the typed {@code text()} deserialization path (JSON → record)
 * runs for real instead of being mocked away. Constructed directly (no Spring proxy), so the
 * {@code @Retry}/{@code @CircuitBreaker} annotations do NOT fire here; that behaviour is proven
 * separately in {@link ResolverResilienceTest}.
 *
 * <p>Day 14: the prompt provider is the REAL one, reading the real file. The grounding instruction
 * lives in that file, and a mocked provider returning "test system prompt" would let the assertions
 * below pass against a prompt that does not contain the rule they are checking for.
 */
class ResolverServiceTest {

    private static final String RETURNS_TICKET = "How long do I have to return something?";

    private static final UUID REFUND_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SHIPPING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
    private final ContextBlockAssembler assembler = new ContextBlockAssembler();

    private ResolverService service() {
        return new ResolverService(client, prompts(), CircuitBreakerRegistry.ofDefaults(), new ToolDefinitions());
    }

    // Day 17: resolve() lives on the tool loop, which wraps ResolverService.ask(). Every stub below
    // answers end_turn, so the loop runs exactly one round and these tests keep measuring what they
    // always measured — the gates and the request — with the loop as a pass-through.
    private ResolverToolLoop resolver() {
        return new ResolverToolLoop(service(), ToolFixtures.registry());
    }

    private static ResolverPromptProvider prompts() {
        try {
            return new ResolverPromptProvider(new ClassPathResource("prompts/resolver_system_prompt.md"));
        } catch (IOException e) {
            throw new IllegalStateException("the resolver prompt must be on the test classpath", e);
        }
    }

    // StructuredMessage is final; Mockito 5's inline mock maker (Boot's default) handles it.
    @SuppressWarnings("unchecked")
    private void stubResponse(StopReason stopReason, String json) {
        StructuredMessage<ResolverOutput> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.ofNullable(stopReason));
        // Real block, not a mock: text() must genuinely deserialize the envelope.
        TextBlock textBlock = TextBlock.builder().text(json).citations(List.of()).build();
        StructuredContentBlock<ResolverOutput> block =
                new StructuredContentBlock<>(ResolverOutput.class, ContentBlock.ofText(textBlock));
        when(message.content()).thenReturn(List.of(block));
        when(client.messages().create(any(StructuredMessageCreateParams.class))).thenReturn(message);
    }

    // ---------------------------------------------------------------- the grounding ledger

    // The receipt: exactly the chunks that were put in front of the model, copied off the context
    // block that produced the request. Not re-derived, not re-sorted, not filtered.
    @Test
    void resolve_recordsExactlyTheSurvivorsFromTheContextBlock() {
        stubResponse(StopReason.END_TURN, envelope("Within 30 days.", false, true, REFUND_ID, SHIPPING_ID));
        ContextBlock context = context(
                chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11),
                chunk(SHIPPING_ID, "Shipping Policy", "Five to seven days.", 0.29));

        Resolution resolution = resolver().resolve(RETURNS_TICKET, context);

        assertThat(resolution.answer()).isEqualTo("Within 30 days.");
        assertThat(resolution.sourcesProvided())
                .extracting(SourceRef::chunkId)
                .containsExactly(REFUND_ID, SHIPPING_ID);
        assertThat(resolution.sourcesProvided())
                .as("the SQL-projected distance survives all the way to the domain object")
                .extracting(SourceRef::distance)
                .containsExactly(0.11, 0.29);
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
    }

    @Test
    void resolve_ledgerIgnoresSourcesTheModelClaimsInItsProse() {
        // ONE-WRITER-PER-FIELD, tested at its sharpest. The model asserts, in the customer-visible
        // reply, that it consulted a warranty document — and retrieval provided only the refund one.
        // The ledger records what was in the REQUEST, so it says refund and nothing else.
        //
        // Getting this wrong is not a cosmetic bug: a citation list assembled from the model's prose
        // is a list the model can hallucinate, and a hallucinated citation is worse than none at all
        // because it looks like evidence.
        stubResponse(StopReason.END_TURN, envelope(
                "According to the Warranty Policy and section 4 of the Returns Guide, you have 30 days.",
                false, true, REFUND_ID));
        ContextBlock context = context(chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11));

        Resolution resolution = resolver().resolve(RETURNS_TICKET, context);

        assertThat(resolution.sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("Refund Policy");
    }

    @Test
    void resolve_anEmptyRetrievalCanNoLongerProduceAConfidentAnswer() {
        // THE DAY 16 INVERSION, and the clearest single statement of what changed.
        //
        // This test used to assert that a whiffed retrieval plus a confidently-answering model
        // produced an empty receipt — "which is what makes an empty receipt on a confident answer a
        // usable smell". A smell is something a human notices later. The gates turn it into an
        // outcome: there were no excerpts, so there is no id the model could legitimately cite, so
        // G4 cannot pass, and the answer never leaves the building.
        //
        // The model here is behaving as badly as it can within the schema — grounded=true on a
        // question nothing in the request could answer — and the assertion is that this is now
        // structurally unable to reach a customer rather than merely detectable afterwards.
        stubResponse(StopReason.END_TURN,
                envelope("ShopFast accepts returns within 30 days.", false, true));

        Resolution resolution = resolver().resolve("Who is ShopFast's CEO?", context());

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.UNVERIFIABLE_CITATIONS);
        assertThat(resolution.answer()).doesNotContain("30 days");
        assertThat(resolution.sourcesProvided()).isEmpty();
        assertThat(resolution.sourcesCited()).isEmpty();
    }

    // ---------------------------------------------------------------- the escalation channels

    // The Day 10 point of the whole migration: the escalation verdict is now DATA copied from the
    // model, not prose a human has to read. Before this, both of these tickets returned RESOLVED and
    // were indistinguishable to any caller.
    @Test
    void resolve_copiesTheModelsEscalateVerdict() {
        // GROUNDED AND ESCALATING AT THE SAME TIME, which is the combination worth pinning: an answer
        // fully supported by the excerpts can still need a person (money in dispute, an action only a
        // human can take). Day 16 must not have collapsed those two channels into one — a gate that
        // treated escalate=true as "not a real answer" would throw away the cited evidence that makes
        // the handoff useful to the agent picking it up.
        stubResponse(StopReason.END_TURN, envelope(
                "Your return is within the window; I'm escalating this to a specialist to process it.",
                true, true, REFUND_ID));

        Resolution escalating = resolver().resolve(RETURNS_TICKET, groundedContext());

        assertThat(escalating.escalate()).isTrue();
        // Still RESOLVED: the model chose to escalate on a HEALTHY, GROUNDED call. ESCALATED_TO_HUMAN
        // would mean either the dependency failed or the grounding gates fired — different facts on
        // different channels.
        assertThat(escalating.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(escalating.escalationCause()).isEqualTo(EscalationCause.NONE);
        assertThat(escalating.sourcesCited()).extracting(SourceRef::chunkId).containsExactly(REFUND_ID);
    }

    @Test
    void resolve_carriesEscalateFalseThrough() {
        stubResponse(StopReason.END_TURN, envelope("Within 30 days.", false, true, REFUND_ID));

        assertThat(resolver().resolve(RETURNS_TICKET, groundedContext()).escalate()).isFalse();
    }

    // A truncated or refused response has no usable reply, and the resolver has no honest neutral
    // answer to invent — so it surfaces rather than degrading. ESCALATED_TO_HUMAN is reserved for
    // dependency health and would be a lie here.
    @Test
    void resolve_surfacesRatherThanDegradingOnBadStopReason() {
        stubResponse(StopReason.MAX_TOKENS, "{\"reply\":\"truncated mid-sent");

        assertThatThrownBy(() -> resolver().resolve(RETURNS_TICKET, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max_tokens");
    }

    // ---------------------------------------------------------------- the grounding gates (Day 16)
    //
    // The prompt's contract is an instruction; these are the enforcement. Each test drives the model
    // stub to misbehave in one specific way and asserts the misbehaviour did not reach a customer.

    // G3. The model volunteers that it is not grounded — and answers anyway, which is the case the
    // discard exists for. Clause (c) tells it to leave the reply empty, so a well-behaved model never
    // reaches this; a badly-behaved one must not be able to talk its way past the gate.
    @Test
    void resolve_groundedFalse_escalatesAndNoAnswerContentLeaks() {
        stubResponse(StopReason.END_TURN, envelope(
                "ShopFast accepts returns within 30 days and gift cards are non-refundable.",
                false, false));

        Resolution resolution = resolver().resolve(RETURNS_TICKET, groundedContext());

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.UNGROUNDED);
        // NOT a substring check on the whole reply — every distinctive fragment of it, because a
        // partial leak is the failure mode worth catching. A gate that discarded the first paragraph
        // and kept the second would still pass a naive isNotEqualTo.
        assertThat(resolution.answer())
                .doesNotContain("30 days")
                .doesNotContain("non-refundable")
                .doesNotContain("ShopFast accepts");
        // Both channels, so a caller reading only `escalate` still routes this to a person.
        assertThat(resolution.escalate()).isTrue();
        // No receipt: retrieval SUCCEEDED here and a full ledger existed, so this is the gate
        // actively dropping it rather than there being nothing to drop.
        assertThat(resolution.sourcesProvided()).isEmpty();
        assertThat(resolution.sourcesCited()).isEmpty();
    }

    // G4, the foreign-id half. The model claims grounding and cites something it was never shown.
    @Test
    void resolve_groundedTrueWithAForeignCitation_escalatesAndWarnsWithTheOffendingId() {
        UUID neverRetrieved = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        stubResponse(StopReason.END_TURN,
                envelope("Within 30 days.", false, true, REFUND_ID, neverRetrieved));

        Resolution resolution = withCapturedLogs(() -> resolver().resolve(RETURNS_TICKET, groundedContext()));

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.UNVERIFIABLE_CITATIONS);
        assertThat(resolution.answer()).doesNotContain("30 days");

        // The WARN is not decoration — it is the Day 24 telemetry seam, and the ID is what makes it
        // actionable. "1 foreign citation" tells an operator something is wrong; the value tells them
        // WHICH kind (a leaked few-shot placeholder, a chunk from an earlier turn, an invention), and
        // those want different fixes.
        assertThat(warnMessages())
                .anySatisfy(line -> assertThat(line)
                        .contains("G4")
                        .contains(neverRetrieved.toString()));
        // The legitimately-cited id is NOT what makes this fail, so it must not be reported as the
        // offender — a WARN that named every citation would bury the one that matters.
        assertThat(warnMessages())
                .anySatisfy(line -> assertThat(line).doesNotContain("foreign=[" + REFUND_ID));
    }

    // G4, the empty half. The cheapest way to satisfy a grounding contract without doing any
    // grounding is to assert the verdict and skip the evidence, so an empty list on grounded=true is
    // a violation rather than "no citations offered, carry on".
    @Test
    void resolve_groundedTrueWithEmptyCitations_escalates() {
        stubResponse(StopReason.END_TURN, envelope("Within 30 days.", false, true));

        Resolution resolution = withCapturedLogs(() -> resolver().resolve(RETURNS_TICKET, groundedContext()));

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.UNVERIFIABLE_CITATIONS);
        assertThat(warnMessages()).anySatisfy(line -> assertThat(line).contains("cited NOTHING"));
    }

    // The happy path, and the only path on which a customer reads text the model wrote.
    @Test
    void resolve_groundedTrueWithValidCitations_resolvesAndMapsSourcesWithBreadcrumbs() {
        stubResponse(StopReason.END_TURN,
                envelope("Within 30 days of delivery.", false, true, REFUND_ID));
        ContextBlock context = context(
                chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11),
                chunk(SHIPPING_ID, "Shipping Policy", "Five to seven days.", 0.29));

        Resolution resolution = resolver().resolve(RETURNS_TICKET, context);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.NONE);
        assertThat(resolution.answer()).isEqualTo("Within 30 days of delivery.");
        // The LEDGER is untouched by the citation: both chunks were shown, and the gate does not get
        // to edit the record of what was sent just because the model only used one of them.
        assertThat(resolution.sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("Refund Policy", "Shipping Policy");
        // The CITED list is the strict subset, carrying the breadcrumb and the distance from the
        // ledger entry — never a bare string re-derived from what the model said.
        assertThat(resolution.sourcesCited())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("Refund Policy");
        assertThat(resolution.sourcesCited().getFirst().distance()).isEqualTo(0.11);
        assertThat(resolution.sourcesCited().getFirst().chunkId()).isEqualTo(REFUND_ID);
    }

    @Test
    void resolve_citedSourcesFollowTheLedgersOrderAndCollapseDuplicates() {
        // The model cites the two chunks in reverse rank order and repeats one. Neither is a
        // violation — nothing in the contract says citations are ordered or unique — but both would
        // otherwise reach a CACHED response, where an incidental model choice becomes a stored
        // difference between two outcomes that are the same. So the ledger's canonical order wins and
        // the duplicate collapses.
        stubResponse(StopReason.END_TURN, envelope(
                "Both apply.", false, true, SHIPPING_ID, REFUND_ID, SHIPPING_ID));
        ContextBlock context = context(
                chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11),
                chunk(SHIPPING_ID, "Shipping Policy", "Five to seven days.", 0.29));

        Resolution resolution = resolver().resolve(RETURNS_TICKET, context);

        assertThat(resolution.sourcesCited())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("Refund Policy", "Shipping Policy");
    }

    // GATE 0. A payload that will not deserialize is thrown as its OWN type, which is what routes it
    // to the retry allowlist and then to a human instead of to a 500. Asserted here as the throw;
    // ResolverResilienceTest proves the retry-then-escalate behaviour through the live proxy.
    @Test
    void resolve_anUnreadableStructuredOutput_throwsTheRetryableGateZeroType() {
        stubResponse(StopReason.END_TURN, "{\"reply\": \"unterminated, and then nothing");

        assertThatThrownBy(() -> resolver().resolve(RETURNS_TICKET, groundedContext()))
                .isInstanceOf(ResolverOutputUnusableException.class)
                // NOT an IllegalStateException, and that is the whole point of the type: that one is
                // reserved for "our bug", is on no allowlist, and would surface this as a 500.
                .isNotInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- prompt assembly (Decision 3)

    @Test
    void theCachedPrefixIsByteIdenticalAcrossTwoDifferentRetrievals() {
        // THE prefix-stability assertion, and the one that protects ADR-020's economics. Anything
        // per-request that leaked in front of the cache_control breakpoint would mean paying a cache
        // WRITE surcharge on every ticket and never earning a read — a ~25% overcharge that shows up
        // as a slightly larger bill and absolutely nothing else.
        //
        // Two genuinely different retrievals, because a test using the same context twice would pass
        // against an implementation that cheerfully interpolated documents into the system block.
        String prefixA = systemPrefix(params(context(chunk(REFUND_ID, "Refund Policy", "30 days", 0.1))));
        String prefixB = systemPrefix(params(context(chunk(SHIPPING_ID, "Shipping Policy", "5 days", 0.4))));

        assertThat(prefixB).isEqualTo(prefixA);
    }

    @Test
    void theGroundingInstructionSitsInsideTheCachedPrefix() {
        MessageCreateParams params = params(context(chunk(REFUND_ID, "Refund Policy", "30 days", 0.1)));
        List<TextBlockParam> systemBlocks = params.system().orElseThrow().textBlockParams().orElseThrow();

        // It is in the system block...
        assertThat(systemBlocks).hasSize(1);
        assertThat(systemBlocks.getFirst().text())
                .contains("Answer only from the provided documents");
        // ...and that block is the one carrying the breakpoint, which is what "before the breakpoint"
        // means operationally: the instruction is billed at 0.1x from the second call onward instead
        // of at full price on every ticket forever.
        assertThat(systemBlocks.getFirst().cacheControl())
                .as("the grounding line only rides the prefix cache if the block carrying it is marked")
                .isPresent();
        // And it is NOT repeated after the breakpoint. A duplicate in the user turn would be paid for
        // on every request and would say the same thing twice.
        assertThat(userTurn(params)).doesNotContain("Answer only from the provided documents");
    }

    @Test
    void documentsPrecedeTheTicketInTheUserTurn() {
        // Order is a safety property, not formatting. The ticket is the only part of this turn a
        // customer controls; putting it last means an injected instruction is arguing against
        // reference material the model has already read, rather than framing material it is about to
        // read. It also puts the real question closest to the generation point.
        String turn = userTurn(params(context(chunk(REFUND_ID, "Refund Policy", "30 days", 0.1))));

        assertThat(turn.indexOf("<documents>")).isNotNegative();
        assertThat(turn.indexOf("<documents>")).isLessThan(turn.indexOf("customer ticket:"));
        assertThat(turn.indexOf("</documents>")).isLessThan(turn.indexOf("customer ticket:"));
        assertThat(turn).endsWith(RETURNS_TICKET);
    }

    @Test
    void theUserTurnCarriesTheAssemblersBytesUntouched() {
        // paramsFor is not allowed to reformat, truncate or re-order the block. It cannot be: the
        // response cache key hashes exactly these bytes, so a "small tweak" at this call site would
        // change every key in the keyspace without changing anything the assembler can see.
        ContextBlock context = context(
                chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11),
                chunk(SHIPPING_ID, "Shipping Policy", "Five to seven days.", 0.29));

        assertThat(userTurn(params(context))).startsWith(context.rendered());
    }

    // GUARD TEST, not ceremony. Both transports share paramsFor so the prompt and schema can't drift,
    // and the streaming path unwraps those params via rawParams(). If output_config failed to survive
    // that unwrapping, the streamed request would silently revert to prose, StreamingReplyExtractor
    // would never match a "reply" key, and every SSE customer would receive an EMPTY stream — a total
    // failure with no exception anywhere to catch it. This assertion is the only thing standing
    // between that bug and production.
    @Test
    void buildStreamingParams_carriesOutputConfigSoTheStreamStaysSchemaEnforced() {
        assertThat(params(context()).outputConfig()).isPresent();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * The built request, reached through {@code buildStreamingParams} because that is the one public
     * door onto {@code paramsFor}'s output. Both transports route through it, so what is asserted here
     * is what the blocking path sends too.
     */
    private MessageCreateParams params(ContextBlock context) {
        return service().buildStreamingParams(RETURNS_TICKET, context);
    }

    private static String systemPrefix(MessageCreateParams params) {
        return params.system().orElseThrow().textBlockParams().orElseThrow().getFirst().text();
    }

    private static String userTurn(MessageCreateParams params) {
        return params.messages().getFirst().content().asString();
    }

    private ContextBlock context(RetrievedChunk... chunks) {
        return assembler.assemble(List.of(chunks));
    }

    /** The default one-excerpt context, so a test that is not ABOUT retrieval can still pass G4. */
    private ContextBlock groundedContext() {
        return context(chunk(REFUND_ID, "Refund Policy", "Customers have 30 days.", 0.11));
    }

    /**
     * Runs {@code call} with a Logback appender attached to {@link ResolverService}'s logger, so the
     * WARN lines the gates emit can be asserted on.
     *
     * <p>Asserting on a log at all is unusual and is justified narrowly here: the G4 WARN is not
     * diagnostic chatter, it is the only OUTPUT of a decision the customer never sees. The response
     * says "a human will take this" whether the model cited a foreign id or the knowledge base was
     * simply silent, so without this line the two are indistinguishable from outside — and one of
     * them means the model is misbehaving. It is also the seam Day 24 builds its counter on.
     */
    private <T> T withCapturedLogs(java.util.function.Supplier<T> call) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ResolverService.class);
        logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            return call.get();
        } finally {
            logger.detachAppender(logs);
        }
    }

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

    private List<String> warnMessages() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * A Day 16 envelope, built rather than hand-written, because the citation ids have to agree with
     * a {@link ContextBlock} fixture and a typo in a uuid string is a silent G4 escalation rather
     * than a compile error.
     */
    private static String envelope(String reply, boolean escalate, boolean grounded, UUID... citations) {
        String ids = Arrays.stream(citations)
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"reply\":\"" + reply + "\",\"citations\":" + ids
                + ",\"escalate\":" + escalate + ",\"grounded\":" + grounded + "}";
    }

    private static RetrievedChunk chunk(UUID id, String breadcrumb, String content, double distance) {
        return new RetrievedChunk(id, breadcrumb.toLowerCase().replace(' ', '-') + ".md", 0,
                breadcrumb, content, 10, distance);
    }
}
