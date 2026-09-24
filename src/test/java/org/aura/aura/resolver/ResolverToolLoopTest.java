package org.aura.aura.resolver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.ContextBlockAssembler;
import org.aura.aura.retrieval.RetrievedChunk;
import org.aura.aura.tools.ToolDefinitions;
import org.aura.aura.tools.ToolFixtures;
import org.aura.aura.tools.ToolRegistry;
import org.aura.aura.tools.backoffice.FakeRefundService;
import org.aura.aura.tools.backoffice.FakeTicketService;
import org.aura.aura.tools.backoffice.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.aura.aura.resolver.ToolTurns.endTurn;
import static org.aura.aura.resolver.ToolTurns.maxTokens;
import static org.aura.aura.resolver.ToolTurns.text;
import static org.aura.aura.resolver.ToolTurns.toolUse;
import static org.aura.aura.resolver.ToolTurns.toolUseTurn;
import static org.aura.aura.tools.ToolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Day 17 tool loop, with the LLM stubbed at the SDK boundary and everything below it real: the
 * request builder, the registry, the executors and the seeded fakes.
 *
 * <p>Responses are genuine SDK {@code Message} objects parsed from wire JSON ({@link ToolTurns}),
 * because the protocol under test — echo the assistant turn verbatim, answer every block by id in one
 * user message — is only observable on real objects. Constructed without Spring, so no retries fire
 * here; the airbag rule through the live proxy is {@link ResolverResilienceTest}'s job.
 */
class ResolverToolLoopTest {

    private static final String TICKET = "Where is my order SF-4412?";
    private static final UUID SHIPPING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
    private final ContextBlock context = new ContextBlockAssembler().assemble(List.of(new RetrievedChunk(
            SHIPPING_ID, "shipping-policy.md", 0, "Shipping Policy",
            "Standard orders ship within two business days.", 10, 0.2)));

    // ---------------------------------------------------------------- one round

    @Test
    void aSingleRound_dispatchesTheCall_echoesTheTurnVerbatim_andTheAnswerPassesTheGates() {
        StructuredMessage<ResolverOutput> lookup = toolUseTurn(
                toolUse("toolu_01", "get_order_status", "{\"order_id\":\"SF-4412\"}"));
        stub(lookup, endTurn(envelope("Your order SF-4412 shipped yesterday with FastShip.", SHIPPING_ID)));

        Resolution resolution = loop().resolve(TICKET, context);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolution.answer()).contains("FastShip");
        assertThat(resolution.toolsInvoked()).containsExactly("get_order_status");
        assertThat(resolution.toolTouched()).isTrue();

        List<StructuredMessageCreateParams<ResolverOutput>> requests = requests(2);
        List<MessageParam> second = requests.get(1).rawParams().messages();
        // opening user turn → the assistant turn VERBATIM → one user message of results
        assertThat(second).hasSize(3);
        assertThat(second.get(1)).isEqualTo(lookup.rawMessage().toParam());
        ToolResultBlockParam result = onlyToolResults(second.get(2)).getFirst();
        assertThat(result.toolUseId()).isEqualTo("toolu_01");
        assertThat(result.isError()).contains(false);
        JsonNode payload = json(result.content().orElseThrow().asString());
        assertThat(payload.path("status").asText()).isEqualTo("shipped");
        assertThat(payload.path("shipped_on").asText()).isEqualTo("2026-09-22");
    }

    // ---------------------------------------------------------------- parallel round

    @Test
    void aParallelRound_answersEveryBlockInExactlyOneUserMessage_withIdsMatched() {
        // Text FIRST, then two tool_use blocks: the block-type iteration must skip the text and must
        // not stop at the first call. content().get(0) would be wrong on both counts.
        stub(toolUseTurn(
                        text("Let me check both of those orders."),
                        toolUse("toolu_A", "get_order_status", "{\"order_id\":\"SF-4412\"}"),
                        toolUse("toolu_B", "get_order_status", "{\"order_id\":\"SF-9999\"}")),
                endTurn(envelope("SF-4412 has shipped; I couldn't find SF-9999.", SHIPPING_ID)));

        Resolution resolution = loop().resolve(TICKET, context);

        assertThat(resolution.toolsInvoked()).containsExactly("get_order_status", "get_order_status");
        List<MessageParam> second = requests(2).get(1).rawParams().messages();
        assertThat(second).as("ONE user message for the whole turn, not one per result").hasSize(3);
        assertThat(second.get(2).role()).isEqualTo(MessageParam.Role.USER);

        List<ToolResultBlockParam> results = onlyToolResults(second.get(2));
        assertThat(results).extracting(ToolResultBlockParam::toolUseId).containsExactly("toolu_A", "toolu_B");
        // The unknown order is an OUTCOME (is_error=false), matched to ITS block — not an error, and not
        // attached to the wrong id.
        assertThat(json(results.get(1).content().orElseThrow().asString()).path("reason").asText())
                .isEqualTo("no_such_order");
        assertThat(results.get(1).isError()).contains(false);
    }

    // ---------------------------------------------------------------- the round cap

    @Test
    void aModelThatNeverStopsAskingIsEscalatedAtTheCap_asAnIncident() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenAnswer(call -> toolUseTurn(toolUse("toolu_" + UUID.randomUUID(), "get_order_status",
                        "{\"order_id\":\"SF-4412\"}")));

        Resolution resolution = loop().resolve(TICKET, context);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.ESCALATED_TO_HUMAN);
        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.TOOL_ROUNDS_EXHAUSTED);
        // Incident-shaped: never cached (Day 16 differential policy), on either count.
        assertThat(resolution.isIncidentalOutcome()).isTrue();
        assertThat(resolution.toolTouched()).isTrue();
        // MAX_TOOL_ROUNDS dispatched rounds, MAX_TOOL_ROUNDS + 1 model calls — the last request is
        // never answered.
        assertThat(resolution.toolsInvoked()).hasSize(ResolverToolLoop.MAX_TOOL_ROUNDS);
        verify(client.messages(), times(ResolverToolLoop.MAX_TOOL_ROUNDS + 1))
                .create(any(StructuredMessageCreateParams.class));
    }

    // ---------------------------------------------------------------- faults stay payload

    @Test
    void anExecutorFaultReturnsToTheModelAsIsError_andTheLoopCarriesOn() {
        OrderService broken = mock(OrderService.class);
        when(broken.findOrder(anyString())).thenThrow(new IllegalStateException("OMS down"));
        ToolRegistry registry = ToolFixtures.registry(broken, new FakeTicketService(), new FakeRefundService());
        stub(toolUseTurn(toolUse("toolu_01", "get_order_status", "{\"order_id\":\"SF-4412\"}")),
                endTurn(envelope("I can't see live order data right now; shipping takes two days.", SHIPPING_ID)));

        Resolution resolution = new ResolverToolLoop(service(), registry).resolve(TICKET, context);

        // No exception escaped into the loop (and so none could have reached resilience machinery).
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        ToolResultBlockParam result = onlyToolResults(requests(2).get(1).rawParams().messages().get(2)).getFirst();
        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().asString()).contains("order_service_unavailable");
    }

    @Test
    void aToolNameTheModelInvented_isAnsweredUnknownTool_notThrown() {
        stub(toolUseTurn(toolUse("toolu_01", "delete_customer", "{}")),
                endTurn(envelope("Shipping takes two business days.", SHIPPING_ID)));

        loop().resolve(TICKET, context);

        ToolResultBlockParam result = onlyToolResults(requests(2).get(1).rawParams().messages().get(2)).getFirst();
        assertThat(result.isError()).contains(true);
        assertThat(result.content().orElseThrow().asString()).contains("unknown_tool");
    }

    // ---------------------------------------------------------------- gates + truncation

    @Test
    void aToolAssistedAnswerEarnsNoGateExemption() {
        // grounded=true with NO citations after a lookup: G4 still fires. Tool use adds no bypass.
        stub(toolUseTurn(toolUse("toolu_01", "get_order_status", "{\"order_id\":\"SF-4412\"}")),
                endTurn("{\"reply\":\"It shipped.\",\"citations\":[],\"escalate\":false,\"grounded\":true}"));

        Resolution resolution = loop().resolve(TICKET, context);

        assertThat(resolution.escalationCause()).isEqualTo(EscalationCause.UNVERIFIABLE_CITATIONS);
        // A grounding refusal would normally be cached; after a tool it must not be.
        assertThat(resolution.toolTouched()).isTrue();
    }

    @Test
    void maxTokensIsReaskedOnceWithARaisedCap() {
        stub(maxTokens(), endTurn(envelope("Shipping takes two business days.", SHIPPING_ID)));

        Resolution resolution = loop().resolve(TICKET, context);

        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        List<StructuredMessageCreateParams<ResolverOutput>> requests = requests(2);
        assertThat(requests.get(0).rawParams().maxTokens()).isEqualTo(ResolverService.MAX_TOKENS);
        assertThat(requests.get(1).rawParams().maxTokens()).isEqualTo(ResolverToolLoop.RAISED_MAX_TOKENS);
    }

    @Test
    void aToolFreeTicketIsOneCall_advertisingTheToolsSortedByName() {
        stub(endTurn(envelope("Shipping takes two business days.", SHIPPING_ID)));

        Resolution resolution = loop().resolve("How long does shipping take?", context);

        assertThat(resolution.toolTouched()).isFalse();
        assertThat(requests(1).getFirst().rawParams().tools().orElseThrow())
                .extracting(tool -> tool.asTool().name())
                .containsExactly("create_followup_ticket", "get_order_status", "initiate_refund");
    }

    // ---------------------------------------------------------------- fixtures

    private ResolverService service() {
        try {
            return new ResolverService(client,
                    new ResolverPromptProvider(new ClassPathResource("prompts/resolver_system_prompt.md")),
                    CircuitBreakerRegistry.ofDefaults(), new ToolDefinitions());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResolverToolLoop loop() {
        return new ResolverToolLoop(service(), ToolFixtures.registry());
    }

    @SafeVarargs
    private void stub(StructuredMessage<ResolverOutput> first, StructuredMessage<ResolverOutput>... rest) {
        when(client.messages().create(any(StructuredMessageCreateParams.class))).thenReturn(first, rest);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<StructuredMessageCreateParams<ResolverOutput>> requests(int expected) {
        ArgumentCaptor<StructuredMessageCreateParams> captor =
                ArgumentCaptor.forClass(StructuredMessageCreateParams.class);
        verify(client.messages(), times(expected)).create(captor.capture());
        return (List) captor.getAllValues();
    }

    /** Asserts the message holds tool_result blocks and NOTHING else — so they are trivially first. */
    private static List<ToolResultBlockParam> onlyToolResults(MessageParam message) {
        List<ContentBlockParam> blocks = message.content().asBlockParams();
        assertThat(blocks).allMatch(ContentBlockParam::isToolResult);
        return blocks.stream().map(ContentBlockParam::asToolResult).toList();
    }

    private static String envelope(String reply, UUID... cited) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : cited) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return "{\"reply\":\"" + reply + "\",\"citations\":[" + ids + "],\"escalate\":false,\"grounded\":true}";
    }
}
