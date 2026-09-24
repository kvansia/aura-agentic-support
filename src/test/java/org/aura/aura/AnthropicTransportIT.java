package org.aura.aura;

import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.client.VoyageTransientException;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.aura.aura.AnthropicMessages.classifierOk;
import static org.aura.aura.AnthropicMessages.error;
import static org.aura.aura.AnthropicMessages.field;
import static org.aura.aura.AnthropicMessages.resolve;
import static org.aura.aura.AnthropicMessages.resolverOk;
import static org.aura.aura.AnthropicMessages.resolverOkDelayed;
import static org.aura.aura.AnthropicMessages.RESOLVER_REPLY;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1(a): the FULL real Spring context with the REAL Anthropic SDK, pointed at a local MockWebServer.
 * The seam is CONFIGURATION ({@code aura.anthropic.base-url}), not a code branch — there is NO Mockito
 * on the client here; the SDK's actual transport (serialization, per-request timeout, error mapping)
 * is what runs.
 *
 * <p>PIPELINE FACT governing every scenario: one {@code /resolve} request is a classifier call THEN a
 * resolver call, both to the SAME server — so every scenario enqueues the classifier-success response
 * FIRST, and request COUNT is (1 classifier + N resolver attempts).
 */
// "test" excludes ConversationRunner + supplies the API key; "it" adds the aggressive MockWebServer-only
// timeout/retry-wait overrides (listed last, so it wins). Evals use "test" alone and keep prod timeouts.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "it"})
@Testcontainers
// Day 14: retrieval is on the request path, so this full application context needs a Postgres. The
// base class supplies a shared one and undoes the test profile's DataSource exclusion. Note what
// that means for the scenarios below: a /resolve request now embeds the ticket and searches pgvector
// BEFORE the Anthropic call this class is about — so the request counts asserted here are still
// (1 classifier + N resolver attempts) against MockWebServer, because Voyage is not this server.
class AnthropicTransportIT extends PostgresBackedContext {

    // Redis stays UP for this whole class — the Day 9 cache runs for real; this class never kills it
    // (that is RedisDegradationIT's job, on its OWN container). @ServiceConnection auto-wires
    // spring.data.redis.* from the container (RedisContainerConnectionDetailsFactory matches it).
    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    // MockWebServer stands in for the Anthropic endpoint. Started in a STATIC INITIALIZER so it is
    // already listening before @DynamicPropertySource's lazy supplier resolves the base-url during
    // context load — a @BeforeAll would race that resolution.
    static final MockWebServer ANTHROPIC = new MockWebServer();
    static {
        try {
            ANTHROPIC.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void seam(DynamicPropertyRegistry registry) {
        // The config seam in action: the REAL client bean reads the SAME key production reads; only the
        // value differs. That identical name is what makes this test exercise the production code path.
        registry.add("aura.anthropic.base-url", () -> ANTHROPIC.url("/").toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        ANTHROPIC.shutdown();
    }

    @Value("${local.server.port}") int port;
    @Autowired CircuitBreakerRegistry breakers;
    RestClient rest;

    /**
     * Day 14: /resolve embeds the ticket before it calls Claude, and this class's MockWebServer is
     * the ANTHROPIC endpoint — Voyage would be reached for real, with the profile's dummy key, and
     * every scenario below would fail on a 401 several layers before the transport it is testing.
     *
     * <p>Stubbed at the Java boundary rather than given a second MockWebServer, because the retrieval
     * leg is not what this class is about and a real embedding call would only add a way for these
     * tests to fail. The request counts below are unaffected either way: 1 classifier + N resolver
     * attempts, all against this server — Voyage is not this server.
     */
    @MockitoBean VoyageEmbeddingClient voyage;

    @Autowired ChunkRepository chunks;
    @Autowired DocumentRepository documents;

    /**
     * Day 16: {@code kb_chunks} is no longer left empty, and the reason is worth stating because the
     * previous comment argued the opposite.
     *
     * <p>An empty corpus used to be the cheapest way to keep this class about transport: retrieval
     * returned an empty document block, Claude answered anyway, and the answer came back. The
     * grounding gates end that — with no excerpts there is no id an answer may cite, so every
     * scripted 200 below would escalate at G4 and every assertion here would be reading a grounding
     * outcome while claiming to test a transport one. One excerpt restores the property this class
     * actually depends on: a scripted success is a success.
     */
    @BeforeEach
    void seedOneExcerpt() {
        KbFixtures.seedOneGroundingChunk(chunks, documents);
    }

    /** The container is shared; leave it as it was found (see RagResolutionIT for the full argument). */
    @AfterAll
    static void clearSharedCorpus(@Autowired ChunkRepository chunks,
                                  @Autowired DocumentRepository documents) {
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @BeforeEach
    void isolate() {
        // FULL WIDTH, not a convenient one-element stub. Learned the hard way: the shared Postgres is
        // shared with RagResolutionIT, so kb_chunks may hold real vector(1024) rows by the time this
        // class runs, and `embedding <=> CAST('[1.0]' AS vector)` is a hard Postgres error on a
        // dimension mismatch — surfacing here as an unexplained 500 in a test about Anthropic. A stub
        // that ignores a contract the database enforces is a stub that fails in test-ordering roulette.
        when(voyage.embedQuery(anyString())).thenReturn(queryVector());
        // A RestClient at the running server, whose error handler is a no-op so 4xx/5xx are returned as
        // data (IT-4 inspects the 500 problem detail) rather than thrown.
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
        // Fresh response queue per test, so a scenario that fails mid-way cannot leak enqueued
        // responses into the next test.
        ANTHROPIC.setDispatcher(new QueueDispatcher());
        // classify() and resolve() feed the SAME "anthropicApi" breaker; reset it so failure counts
        // from one scenario cannot bleed into the next (and, cumulatively, trip it open and change
        // request counts). Each single scenario stays under minimum-number-of-calls, so with the reset
        // every outcome here is a retry/fallback decision, never a breaker-open one.
        breakers.circuitBreaker("anthropicApi").reset();
    }

    @Test
    void it1_happyPath_resolved_exactlyTwoRequests() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it1", "Where is my order #88231?");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("RESOLVED");
        assertThat(field(resp, "resolutionText")).isEqualTo(RESOLVER_REPLY);  // schema-valid, round-tripped
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(2);        // classifier THEN resolver
    }

    @Test
    void it2_transientRecovery_retriesThenResolves_exactlyFourRequests() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(error(429, "rate_limit_error", "slow down"));
        ANTHROPIC.enqueue(error(429, "rate_limit_error", "slow down"));
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it2", "Transient recovery scenario ticket");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("RESOLVED");
        assertThat(field(resp, "resolutionText")).isEqualTo(RESOLVER_REPLY);
        // 4 = classifier(1) + resolver attempts [429, 429, 200]. The retry is proven by a COUNT of
        // requests received, NEVER by an elapsed duration.
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(4);
    }

    /**
     * Decision 5 at the HTTP edge: an unhealthy RETRIEVAL dependency degrades exactly like an
     * unhealthy Claude — 200 with an escalation, not a 500.
     *
     * <p>It lives beside {@code it3_budgetExhausted_escalates_http200} on purpose, because the pair is
     * the whole point. Before Day 14 those two outages produced different customer experiences for no
     * reason a customer could understand: 200-and-a-handoff when Anthropic was rate-limited, a 500
     * when Voyage was. Same class of event, same correct answer — a human.
     *
     * <p>The classifier response is still enqueued and still consumed: classification precedes
     * resolution and is unaffected by retrieval being down. Nothing is enqueued for the resolver,
     * which is the assertion in disguise — this scenario must never reach Claude at all.
     */
    @Test
    void it2b_retrievalUnavailable_escalates_http200_withoutCallingClaude() {
        when(voyage.embedQuery(anyString()))
                .thenThrow(new VoyageTransientException("Voyage transient failure: HTTP 429 on voyage-4-lite"));
        ANTHROPIC.enqueue(classifierOk());
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it2b", "Retrieval unavailable scenario ticket");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("ESCALATED_TO_HUMAN");
        // ONE request: the classifier. Paying Sonnet to answer a grounded question with no documents
        // would be spending money to produce a worse answer than the escalation.
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(1);
    }

    @Test
    void it3_budgetExhausted_escalates_http200() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(error(529, "overloaded_error", "overloaded"));
        ANTHROPIC.enqueue(error(529, "overloaded_error", "overloaded"));
        ANTHROPIC.enqueue(error(529, "overloaded_error", "overloaded"));
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it3", "Budget exhausted scenario ticket");

        // ADR-013: the HTTP STATUS describes transport (200 — we handled it), and the BODY carries the
        // business verdict. Retries exhausted on a transient (529) → the resolver fallback degrades to a
        // human. First end-to-end proof of that path through the real controller.
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("ESCALATED_TO_HUMAN");
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(4);  // classifier + 3 resolver attempts
    }

    @Test
    void it4_permanentFailure_noRetry_problemDetail() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(error(400, "invalid_request_error", "bad request"));
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it4", "Permanent failure scenario ticket");

        // No retry: 400 (BadRequestException) is absent from the retry allowlist. classifier(1) +
        // resolver(1) = 2, verified as a COUNT of requests received.
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(2);
        // VERIFIED actual behaviour (Day 11): a 400 is not in the resolver fallback allowlist, so it
        // re-propagates; there is no @ExceptionHandler for Anthropic SDK errors (the Day 8 502/504
        // mapping is still a stub), so it lands in GlobalExceptionHandler.handleGeneric → HTTP 500,
        // RFC 9457 application/problem+json. A Claude 400 means OUR request was malformed, so 500 ("my
        // side broke") is the honest surface; the 502 stub is reserved for upstream 5xx/timeout, which
        // the escalation path (IT-3/IT-5) exercises instead.
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getHeaders().getContentType())
                .as("RFC 9457 problem detail")
                .isNotNull();
        assertThat(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content type is application/problem+json")
                .isTrue();
    }

    @Test
    void it5_hangConvertedToFallbackOutcome() {
        ANTHROPIC.enqueue(classifierOk());
        // One delayed resolver response per retry attempt; each body is delayed 2s > the 500ms test
        // timeout, so every attempt times out (AnthropicIoException, a transient) and retries exhaust.
        Duration overTimeout = Duration.ofSeconds(2);
        ANTHROPIC.enqueue(resolverOkDelayed(overTimeout, KbFixtures.GROUNDING_CHUNK_ID.toString()));
        ANTHROPIC.enqueue(resolverOkDelayed(overTimeout, KbFixtures.GROUNDING_CHUNK_ID.toString()));
        ANTHROPIC.enqueue(resolverOkDelayed(overTimeout, KbFixtures.GROUNDING_CHUNK_ID.toString()));

        ResponseEntity<String> resp = resolve(rest, "it5", "Hang conversion scenario ticket");

        // The delay lives in the SCRIPT; the assertion reads a BODY FIELD. Duration as assertion =
        // flaky; duration as runtime = just slow. If someone deletes the timeout config, the app waits
        // out the delay, gets a 200 RESOLVED, and this test fails loudly — the Day 9 regression class,
        // machine-caught.
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("ESCALATED_TO_HUMAN");
    }

    // ---------------------------------------------------------------- Day 16: grounding at the edge
    //
    // The gates are unit-tested against a mocked client. These two prove the same decisions survive
    // the whole stack — the real SDK's serialization, the real controller, the real DTO mapping — and
    // in particular that a grounding refusal reaches the customer the way ADR-013 says it must:
    // HTTP 200 carrying a business verdict, never a 4xx/5xx that a client would treat as an error.

    /**
     * IT-6: the model reports that the excerpts do not answer the ticket. That is the contract
     * working, and it must not look like a failure to anyone downstream.
     */
    @Test
    void it6_insufficientContext_escalates_http200_withNoSources() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverInsufficient());
        int before = ANTHROPIC.getRequestCount();

        ResponseEntity<String> resp = resolve(rest, "it6", "Do you offer a student discount?");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("ESCALATED_TO_HUMAN");
        // NOT retried. A refusal is a completed, correct answer — retrying it would spend a second
        // Sonnet call to ask the same question of the same documents and get the same reply.
        assertThat(ANTHROPIC.getRequestCount() - before).isEqualTo(2);
        // Neither list survives an escalation, and the ledger being empty here is the interesting
        // half: retrieval SUCCEEDED, an excerpt was seeded and shown, and the response still carries
        // no receipt — because this reply was produced instead of an answer, not from that document.
        assertThat(array(resp, "sourcesCited")).isEmpty();
        assertThat(array(resp, "sourcesProvided")).isEmpty();
    }

    /**
     * IT-7: a grounded answer citing an id retrieval really supplied — the only path on which a
     * customer reads model-written text, end to end.
     */
    @Test
    void it7_groundedAnswer_carriesTheCitedSourceOnTheWire() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));

        ResponseEntity<String> resp = resolve(rest, "it7", "How long do I have to request a refund?");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("RESOLVED");
        assertThat(field(resp, "resolutionText")).isEqualTo(RESOLVER_REPLY);

        // The citation arrives as a real object with the Day 12 breadcrumb, resolved from the ledger
        // rather than echoed back as the bare string the model sent.
        JsonNode cited = array(resp, "sourcesCited");
        assertThat(cited).hasSize(1);
        assertThat(cited.get(0).path("chunkId").asText())
                .isEqualTo(KbFixtures.GROUNDING_CHUNK_ID.toString());
        assertThat(cited.get(0).path("breadcrumb").asText())
                .isEqualTo("Refund Policy > Standard Refund Window");
        // And the ledger still says what was SHOWN, independently. The two are separate fields
        // because they answer separate questions; here they happen to agree because one excerpt was
        // seeded and it was the one cited.
        assertThat(array(resp, "sourcesProvided")).hasSize(1);
    }

    /**
     * IT-8 (Day 17): one tool round, end to end, through the REAL SDK transport. The scripted model
     * asks for {@code get_order_status(SF-4412)}, then answers.
     *
     * <p>What only a full-stack test can prove: that the request we send after dispatch is a legal
     * Messages API conversation as the SDK serializes it — the assistant turn echoed with its
     * {@code tool_use} block, then a user turn whose {@code tool_result} carries the matching id and the
     * seeded, allowlisted order facts — and that the final answer still flows through G3/G4 and the
     * response DTO like any other.
     */
    @Test
    void it8_toolRound_dispatchesThroughTheRealTransport_andTheAnswerStillPassesTheGates() throws Exception {
        drainRecordedRequests();
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(AnthropicMessages.resolverToolUse(
                "toolu_it8", "get_order_status", "{\"order_id\":\"SF-4412\"}"));
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));

        ResponseEntity<String> resp = resolve(rest, "it8", "Where is my order SF-4412?");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(field(resp, "outcome")).isEqualTo("RESOLVED");
        assertThat(field(resp, "resolutionText")).isEqualTo(RESOLVER_REPLY);

        ANTHROPIC.takeRequest(1, TimeUnit.SECONDS);                                   // classifier
        JsonNode firstResolverCall = AnthropicMessages.MAPPER.readTree(
                ANTHROPIC.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        JsonNode secondResolverCall = AnthropicMessages.MAPPER.readTree(
                ANTHROPIC.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());

        // The advertisement, sorted by name, is on the wire.
        assertThat(firstResolverCall.path("tools").findValuesAsText("name"))
                .containsExactly("create_followup_ticket", "get_order_status", "initiate_refund");

        // user → assistant(tool_use, echoed) → user(tool_result)
        JsonNode messages = secondResolverCall.path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("content").findValuesAsText("id")).contains("toolu_it8");
        JsonNode result = messages.get(2).path("content").get(0);
        assertThat(result.path("type").asText()).isEqualTo("tool_result");
        assertThat(result.path("tool_use_id").asText()).isEqualTo("toolu_it8");
        assertThat(result.path("is_error").asBoolean()).isFalse();
        JsonNode order = AnthropicMessages.MAPPER.readTree(result.path("content").asText());
        assertThat(order.path("status").asText()).isEqualTo("shipped");
        assertThat(order.path("carrier").asText()).isEqualTo("FastShip");
    }

    /** MockWebServer records every request for the life of the class; start IT-8 from an empty log. */
    private static void drainRecordedRequests() throws InterruptedException {
        while (ANTHROPIC.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // discard earlier scenarios' requests
        }
    }

    /** A scripted "the excerpts do not answer this" envelope: grounded=false, nothing cited. */
    private static MockResponse resolverInsufficient() {
        return AnthropicMessages.ok200("claude-sonnet-4-5",
                "{\"reply\":\"\",\"citations\":[],\"escalate\":true,\"grounded\":false}");
    }

    /** Reads a top-level ARRAY field out of the response body (AnthropicMessages.field is strings). */
    private static JsonNode array(ResponseEntity<String> resp, String name) {
        try {
            return AnthropicMessages.MAPPER.readTree(resp.getBody()).path(name);
        } catch (Exception e) {
            throw new RuntimeException("response body was not JSON: " + resp.getBody(), e);
        }
    }
}
