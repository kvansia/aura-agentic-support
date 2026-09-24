package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.aura.aura.tools.backoffice.FakeOrderService;
import org.aura.aura.tools.backoffice.OrderService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.aura.aura.tools.ToolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code get_order_status}: found, not-found-as-PAYLOAD, fault-as-ERROR, and invalid input stopped
 * before the service is ever called. Each test is one row of the "is_error describes execution, not
 * outcome" table.
 */
class GetOrderStatusExecutorTest {

    private final GetOrderStatusExecutor executor =
            new GetOrderStatusExecutor(new FakeOrderService(ToolFixtures.CLOCK));

    @Test
    void aKnownOrderAnswersItsAllowlistedFields_withDatesRelativeToTheFixedClock() {
        ToolResultPayload result = executor.execute("toolu_1", json("{\"order_id\":\"SF-4412\"}"));

        assertThat(result.isError()).isFalse();
        JsonNode body = json(result.content());
        assertThat(body.path("found").asBoolean()).isTrue();
        assertThat(body.path("status").asText()).isEqualTo("shipped");
        assertThat(body.path("carrier").asText()).isEqualTo("FastShip");
        // "yesterday" relative to the injected clock (2026-09-23) — never the wall clock.
        assertThat(body.path("shipped_on").asText()).isEqualTo("2026-09-22");
        // ALLOWLIST, asserted as the complete key set: a field added to Order later must not start
        // flowing into the prompt until someone adds it to the executor on purpose.
        assertThat(body.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("found", "order_id", "status", "carrier", "shipped_on", "items");
    }

    @Test
    void anUnknownOrderIsABusinessOutcome_okNotError() {
        ToolResultPayload result = executor.execute("toolu_1", json("{\"order_id\":\"SF-9999\"}"));

        // is_error describes EXECUTION, not outcome: the lookup worked; the order does not exist.
        assertThat(result.isError()).isFalse();
        assertThat(json(result.content()).path("found").asBoolean()).isFalse();
        assertThat(json(result.content()).path("reason").asText()).isEqualTo("no_such_order");
    }

    @Test
    void aServiceFaultIsAnErrorResult_neverAnException() {
        OrderService broken = mock(OrderService.class);
        when(broken.findOrder(anyString())).thenThrow(new IllegalStateException("OMS connection refused"));

        ToolResultPayload result = new GetOrderStatusExecutor(broken)
                .execute("toolu_1", json("{\"order_id\":\"SF-4412\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(json(result.content()).path("error").asText()).isEqualTo("order_service_unavailable");
        // The fault text is logged, not handed to the model: nothing of "connection refused" leaks.
        assertThat(result.content()).doesNotContain("refused");
    }

    @Test
    void invalidInputIsRejectedBeforeAnyServiceCall() {
        OrderService orders = mock(OrderService.class);
        GetOrderStatusExecutor guarded = new GetOrderStatusExecutor(orders);

        // Pattern violation, a smuggled extra property, a wrong type, and a missing required field —
        // the model is an untrusted client, so each is stopped at the schema, not at the service.
        for (String bad : new String[] {
                "{\"order_id\":\"4412\"}",
                "{\"order_id\":\"SF-4412\",\"include_address\":true}",
                "{\"order_id\":4412}",
                "{}",
                "[\"SF-4412\"]"}) {
            ToolResultPayload result = guarded.execute("toolu_1", json(bad));
            assertThat(result.isError()).as(bad).isTrue();
            assertThat(json(result.content()).path("error").asText()).as(bad).isEqualTo("invalid_input");
        }
        verifyNoInteractions(orders);
    }
}
