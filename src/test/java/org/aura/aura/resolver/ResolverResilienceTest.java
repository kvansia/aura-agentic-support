package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.tools.ToolRegistry;
import org.aura.aura.tools.backoffice.FakeTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the Day 8 resilience policy on {@link ResolverService#ask}. Each
 * test name states one sentence of the policy; together they ARE the policy's documentation.
 *
 * <p>These run against a real (sliced) Spring context, not a hand-constructed {@code new
 * ResolverService(...)} — that is the whole point. The {@code @Retry}/{@code @CircuitBreaker}
 * behaviour lives in the Spring AOP proxy, so it only exists when the bean is proxied. A plain
 * constructor call (see {@link ResolverServiceTest}) would silently exercise no resilience at all.
 *
 * <p>The context is deliberately narrow: it imports only {@link ResolverService} and its collaborators
 * plus Resilience4j's autoconfiguration. Booting the whole app instead would drag in the
 * {@code ConversationRunner} CommandLineRunner, which fires a live resolve on startup.
 */
@SpringBootTest(
        classes = ResolverResilienceTest.ResilienceTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // Keep the REAL policy (the transient allowlist, the breaker thresholds) from application.yml,
        // but collapse the back-off so the retry test asserts a COUNT without sleeping ~3s for it.
        "resilience4j.retry.instances.anthropicApi.wait-duration=10ms",
        "resilience4j.retry.instances.anthropicApi.enable-exponential-backoff=false"
})
class ResolverResilienceTest {

    private static final String TICKET = "How long do I have to return something?";

    // A fixed, already-retrieved context. These tests are about TRANSPORT health, so retrieval is a
    // constant here rather than a moving part — which is precisely the separation that moving
    // retrieval out of ResolverService bought.
    private static final ContextBlock CONTEXT = new ContextBlock(
            "<documents>\n<document id=\"c1\" breadcrumb=\"Refund Policy\">\n30 days\n</document>\n</documents>",
            List.of(new SourceRef(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                    "Refund Policy", 0.19)));

    @Configuration(proxyBeanMethods = false)
    // pulls in Resilience4j + Spring AOP autoconfiguration, and application.yml binding. The exclusion
    // is Day 13: this slice does not use a database, and it activates no profile, so it cannot inherit
    // application-test.yml's exclusion — see ClassificationResilienceTest for the same note.
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    // Day 14: HardcodedKnowledgeBase is gone from this slice — retrieval no longer happens inside
    // ResolverService, so the policy under test now wraps a pure "ask Claude" call. That is not the
    // test being simplified, it is the property being preserved: a retry must not re-run a billable
    // embedding, and every attempt must ask about the SAME context it started with.
    // Day 17: the policy is on ResolverService.ask (one model call), and the tests drive it through the
    // ResolverToolLoop — the real caller — so what is proven is that the proxy is live on the path
    // production takes. The tool package is scanned in whole (definitions, registry, executors, fakes)
    // so the registry boot check runs here too.
    @Import({ResolverService.class, ResolverToolLoop.class, ResolverPromptProvider.class})
    @ComponentScan(basePackageClasses = ToolRegistry.class)
    static class ResilienceTestConfig {
        // The one external dependency is faked. Deep stubs let client.messages().create(...) be stubbed
        // without naming the intermediate service type. Autowired into the test as the SAME instance.
        @Bean
        AnthropicClient anthropicClient() {
            return mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        }
    }

    @Autowired
    AnthropicClient client;

    @Autowired
    ResolverToolLoop resolver; // calls the AOP-proxied ResolverService — annotations are live on every round

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    FakeTicketService tickets; // the real in-memory ledger the loop's dispatch writes to

    @BeforeEach
    void resetSharedState() {
        // Both the mock and the breaker are context-scoped singletons shared across test methods.
        // Reset both so one test's failures can't bleed into the next (e.g. push the breaker toward
        // OPEN, or leave stale stubbing). Reset in @BeforeEach, not @AfterEach, so each test starts
        // from a known CLOSED breaker regardless of JUnit's method order.
        reset(client);
        circuitBreakers.circuitBreaker("anthropicApi").reset();
    }

    // POLICY: a transient rate-limit is retried, and a call that succeeds within the attempt budget
    // returns the real answer. Two 429s then a success ⇒ exactly three network attempts.
    @Test
    void retriesOnRateLimitThenSucceeds() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenThrow(rateLimited())
                .thenThrow(rateLimited())
                .thenReturn(okResponse());

        Resolution resolution = resolver.resolve(TICKET, CONTEXT);

        // Succeeded on the third attempt: a normal, grounded RESOLVED answer — NOT an escalation.
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        // BOTH channels agree it is a clean answer: healthy dependency, and no model-chosen handoff.
        assertThat(resolution.escalate()).isFalse();
        // The ledger is the context handed in, carried through a successful retry unchanged.
        assertThat(resolution.sourcesProvided())
                .extracting(SourceRef::breadcrumb)
                .containsExactly("Refund Policy");
        // The retry actually re-called Claude: 1 original + 2 retries = 3 (max-attempts is a total).
        verify(client.messages(), times(3)).create(any(StructuredMessageCreateParams.class));
    }

    // POLICY: a permanent error is NOT retried — it propagates. A 400 is our malformed request;
    // retrying it as-is only wastes calls. The absence-of-retry is the assertion: exactly one attempt.
    @Test
    void doesNotRetryOnBadRequest() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenThrow(badRequest());

        // Propagates (does not get swallowed into a bogus escalation) — the fallback is typed to the
        // breaker-open exception only, so a permanent error flows straight through to the caller.
        assertThatThrownBy(() -> resolver.resolve(TICKET, CONTEXT))
                .isInstanceOf(BadRequestException.class);

        verify(client.messages(), times(1)).create(any(StructuredMessageCreateParams.class));
    }

    // POLICY: a transient failure that never clears within the attempt budget degrades to human
    // escalation, NOT a 5xx. Three 429s exhaust the retries; the caller still gets an ESCALATED result
    // (a 200-worthy degraded answer) rather than the exception. Contrast doesNotRetryOnBadRequest: a
    // permanent error is not on the transient allowlist, so it propagates instead of degrading.
    @Test
    void escalatesWhenRetriesExhausted() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenThrow(rateLimited())
                .thenThrow(rateLimited())
                .thenThrow(rateLimited());

        Resolution resolution = resolver.resolve(TICKET, CONTEXT);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        // The fallback sets BOTH channels: status says WHY (dependency unhealthy), escalate says WHAT
        // to do now (route to a human), so a caller reading only escalate still behaves correctly.
        assertThat(resolution.escalate()).isTrue();
        // Full retry budget spent (3 attempts) before degrading — not a silent single-shot give-up.
        verify(client.messages(), times(3)).create(any(StructuredMessageCreateParams.class));
    }

    // POLICY: when the breaker is OPEN, resolve short-circuits to human escalation without touching
    // the network. Drive the breaker OPEN via the registry (deterministic — no need to manufacture a
    // failure storm), then assert the degraded outcome and that Claude was never called.
    @Test
    void fallsBackToHumanEscalationWhenCircuitOpen() {
        circuitBreakers.circuitBreaker("anthropicApi").transitionToOpenState();

        Resolution resolution = resolver.resolve(TICKET, CONTEXT);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalate()).isTrue();
        verify(client.messages(), never()).create(any(StructuredMessageCreateParams.class));
    }

    /**
     * GATE 0, end to end through the live proxy: an unreadable structured output is RETRIED like a
     * transient, and only escalates once the budget is spent.
     *
     * <p>This is the assertion the gate-0 policy actually rests on, and it cannot be made in a unit
     * test: {@code retry-exceptions} is a list of class names in {@code application.yml} matched
     * reflectively by Resilience4j at runtime, so a typo there compiles, passes every mocked test,
     * and silently converts "retry twice then hand to a human" into "escalate on the first malformed
     * token". The COUNT is what proves the list entry resolves.
     *
     * <p>Three attempts and not one is the whole point: the call is a pure read at temperature 1.0,
     * so a second sample is a genuinely different draw, and most unreadable outputs are a one-off.
     * Escalating immediately would hand a person a ticket the model could have answered on the next
     * try.
     */
    @Test
    void anUnreadableStructuredOutputIsRetriedAndThenEscalates() {
        // Built BEFORE the outer stubbing starts. unreadableResponse() stubs a mock of its own, and
        // Mockito treats a nested when(...) inside an unfinished when(...) as UnfinishedStubbing —
        // which surfaces as a failure in whichever test runs next, not this one.
        StructuredMessage<ResolverOutput> first = unreadableResponse();
        StructuredMessage<ResolverOutput> second = unreadableResponse();
        StructuredMessage<ResolverOutput> third = unreadableResponse();
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenReturn(first)
                .thenReturn(second)
                .thenReturn(third);

        Resolution resolution = resolver.resolve(TICKET, CONTEXT);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        // OUTPUT_UNUSABLE, not DEPENDENCY_UNAVAILABLE. The dependency was healthy throughout — it
        // answered three times, quickly — and the cause is what keeps the cache from storing this
        // (a bad generation is a property of one call) and what lets Day 24 count it separately from
        // an outage.
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.OUTPUT_UNUSABLE);
        assertThat(resolution.escalate()).isTrue();
        verify(client.messages(), times(3)).create(any(StructuredMessageCreateParams.class));
    }

    /**
     * Day 17 — THE AIRBAG RULE, through the live proxy. Round 1 asks for a WRITE (a follow-up ticket);
     * round 2's model call is rate-limited once and then succeeds.
     *
     * <p>What Resilience4j retries is round 2's single model call, re-sending the same immutable
     * conversation — NOT the loop, and so NOT round 1's dispatch. Had the retried unit been "the whole
     * resolve" (as it was before today), that 429 would have replayed the ticket creation. The ledger
     * count is the assertion; the call count proves the retry actually happened.
     */
    @Test
    void aRetryOnRoundTwoNeverReplaysRoundOnesWrite() {
        StructuredMessage<ResolverOutput> wantsATicket = ToolTurns.toolUseTurn(ToolTurns.toolUse("toolu_w1",
                "create_followup_ticket", "{\"team\":\"warehouse\",\"summary\":\"Check stock for SF-4412\"}"));
        StructuredMessage<ResolverOutput> answer = okResponse();
        int ticketsBefore = tickets.created().size();
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenReturn(wantsATicket)
                .thenThrow(rateLimited())
                .thenReturn(answer);

        Resolution resolution = resolver.resolve(TICKET, CONTEXT);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolution.toolsInvoked()).containsExactly("create_followup_ticket");
        // round 1 (1) + round 2 [429, 200] (2) = 3 model calls ...
        verify(client.messages(), times(3)).create(any(StructuredMessageCreateParams.class));
        // ... and exactly ONE ticket.
        assertThat(tickets.created()).hasSize(ticketsBefore + 1);
    }

    /** A clean end_turn whose payload is not a ResolverOutput — the gate-0 case. */
    @SuppressWarnings("unchecked")
    private static StructuredMessage<ResolverOutput> unreadableResponse() {
        StructuredMessage<ResolverOutput> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.of(StopReason.END_TURN));
        TextBlock textBlock = TextBlock.builder()
                .text("{\"reply\": \"cut off mid-")
                .citations(List.of())
                .build();
        when(message.content()).thenReturn(List.of(
                new StructuredContentBlock<>(ResolverOutput.class, ContentBlock.ofText(textBlock))));
        return message;
    }

    // A well-formed envelope, Day 16 shape: grounded, and citing the one chunk CONTEXT supplies. It
    // has to cite a REAL id from that fixture rather than a placeholder — G4 checks every id against
    // the chunks the request actually carried, so a made-up id here would turn every resilience test
    // into a grounding escalation and the retry/breaker assertions would be measuring the wrong gate.
    //
    // The block is a real SDK object wrapping real JSON rather than a mock, so the typed
    // deserialization the service depends on actually runs in these tests.
    @SuppressWarnings("unchecked")
    private static StructuredMessage<ResolverOutput> okResponse() {
        StructuredMessage<ResolverOutput> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.of(StopReason.END_TURN));
        TextBlock textBlock = TextBlock.builder()
                .text("{\"reply\":\"Returns are accepted within 30 days.\",\"citations\":[\""
                        + CONTEXT.sourcesProvided().getFirst().chunkId()
                        + "\"],\"escalate\":false,\"grounded\":true}")
                .citations(List.of())
                .build();
        when(message.content()).thenReturn(List.of(
                new StructuredContentBlock<>(ResolverOutput.class, ContentBlock.ofText(textBlock))));
        return message;
    }

    // --- helpers: real SDK exception instances of the exact types the policy keys on ----------------
    // Built via the SDK builders (headers + body are required) so they are genuine RateLimitException /
    // BadRequestException instances — that is what Resilience4j's allowlist matching sees at runtime.

    private static RateLimitException rateLimited() {
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from("rate_limited"))
                .build();
    }

    private static BadRequestException badRequest() {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from("bad_request"))
                .build();
    }
}
