package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.aura.aura.tools.backoffice.FakeOrderService;
import org.aura.aura.tools.backoffice.FakeTicketService;
import org.aura.aura.tools.backoffice.TicketService;
import org.junit.jupiter.api.Test;

import static org.aura.aura.tools.ToolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code create_followup_ticket}: a write, idempotent on the tool_use_id, never echoing free text. */
class CreateFollowupTicketExecutorTest {

    private final FakeTicketService tickets = new FakeTicketService();
    private final CreateFollowupTicketExecutor executor =
            new CreateFollowupTicketExecutor(tickets, new FakeOrderService(ToolFixtures.CLOCK));

    private static final String WAREHOUSE_CHECK =
            "{\"order_id\":\"SF-4412\",\"team\":\"warehouse\",\"summary\":\"Customer reports a crushed box; please check stock.\"}";

    @Test
    void createsATicketAndAnswersOnlyAllowlistedFields() {
        ToolResultPayload result = executor.execute("toolu_A", json(WAREHOUSE_CHECK));

        assertThat(result.isError()).isFalse();
        JsonNode body = json(result.content());
        assertThat(body.path("ticket_id").asText()).isEqualTo("FT-0001");
        assertThat(body.path("team").asText()).isEqualTo("warehouse");
        // The summary is model-authored free text: stored for the receiving team, NEVER sent back
        // into the prompt (ADR-047 — a string in a tool result is an injection surface).
        assertThat(result.content()).doesNotContain("crushed");
        assertThat(tickets.created()).hasSize(1);
    }

    @Test
    void theSameToolUseIdTwiceIsOneTicket_aDispatchReplayCreatesNothing() {
        ToolResultPayload first = executor.execute("toolu_A", json(WAREHOUSE_CHECK));
        ToolResultPayload replay = executor.execute("toolu_A", json(WAREHOUSE_CHECK));

        assertThat(json(replay.content()).path("ticket_id").asText())
                .isEqualTo(json(first.content()).path("ticket_id").asText());
        assertThat(tickets.created()).hasSize(1);

        // SCOPE of that guarantee: a DIFFERENT block asking for the same work is a second ticket
        // today. Semantic de-duplication is Day 18 policy, not dispatch plumbing.
        executor.execute("toolu_B", json(WAREHOUSE_CHECK));
        assertThat(tickets.created()).hasSize(2);
    }

    @Test
    void anUnknownOrderIsAPayload_andNoTicketIsCreated() {
        ToolResultPayload result = executor.execute("toolu_A",
                json("{\"order_id\":\"SF-9999\",\"team\":\"shipping\",\"summary\":\"Trace parcel\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(json(result.content()).path("reason").asText()).isEqualTo("no_such_order");
        assertThat(tickets.created()).isEmpty();
    }

    @Test
    void invalidInputIsRejectedBeforeAnyServiceCall() {
        TicketService guardedTickets = mock(TicketService.class);
        CreateFollowupTicketExecutor guarded =
                new CreateFollowupTicketExecutor(guardedTickets, new FakeOrderService(ToolFixtures.CLOCK));

        for (String bad : new String[] {
                "{\"team\":\"legal\",\"summary\":\"x\"}",                        // not in the team enum
                "{\"team\":\"warehouse\"}",                                     // summary required
                "{\"team\":\"warehouse\",\"summary\":\"" + "x".repeat(201) + "\"}", // maxLength 200
                "{\"team\":\"warehouse\",\"summary\":\"x\",\"priority\":\"urgent\"}"}) { // closed schema
            assertThat(json(guarded.execute("toolu_A", json(bad)).content()).path("error").asText())
                    .as(bad).isEqualTo("invalid_input");
        }
        verifyNoInteractions(guardedTickets);
    }

    @Test
    void aServiceFaultIsAnErrorResult_neverAnException() {
        TicketService broken = mock(TicketService.class);
        when(broken.createFollowup(anyString(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("ticketing down"));

        ToolResultPayload result = new CreateFollowupTicketExecutor(broken, new FakeOrderService(ToolFixtures.CLOCK))
                .execute("toolu_A", json(WAREHOUSE_CHECK));

        assertThat(result.isError()).isTrue();
        assertThat(json(result.content()).path("error").asText()).isEqualTo("ticket_service_unavailable");
    }
}
