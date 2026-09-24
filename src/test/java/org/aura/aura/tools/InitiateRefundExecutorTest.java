package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.aura.aura.tools.backoffice.FakeOrderService;
import org.aura.aura.tools.backoffice.FakeRefundService;
import org.aura.aura.tools.backoffice.RefundService;
import org.junit.jupiter.api.Test;

import static org.aura.aura.tools.ToolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code initiate_refund}: DESTRUCTIVE in principle, INERT on Day 17 (ADR-045). */
class InitiateRefundExecutorTest {

    private final FakeRefundService refunds = new FakeRefundService();
    private final InitiateRefundExecutor executor =
            new InitiateRefundExecutor(refunds, new FakeOrderService(ToolFixtures.CLOCK));

    private static final String DAMAGED_REFUND =
            "{\"order_id\":\"SF-2002\",\"amount_cents\":4999,\"currency\":\"CAD\",\"reason\":\"damaged\"}";

    @Test
    void aValidRequestIsAlwaysPendingConfirmation_andNoMoneyMoves() {
        ToolResultPayload result = executor.execute("toolu_R", json(DAMAGED_REFUND));

        assertThat(result.isError()).isFalse();
        JsonNode body = json(result.content());
        assertThat(body.path("refund_id").asText()).isEqualTo("RF-0001");
        assertThat(body.path("refund_status").asText()).isEqualTo("pending_confirmation");

        // THE INERTNESS ASSERTION. The ledger holds a pending request a human must approve, and its
        // completed-movement book is EMPTY — there is no code path from a tool call to moved money
        // until Day 18's confirmation gate exists.
        assertThat(refunds.pending()).singleElement()
                .satisfies(pending -> {
                    assertThat(pending.status()).isEqualTo(RefundService.Status.PENDING_CONFIRMATION);
                    assertThat(pending.amountCents()).isEqualTo(4999);
                    assertThat(pending.currency()).isEqualTo("CAD");
                });
        assertThat(refunds.completedMovements()).isEmpty();
    }

    @Test
    void aReplayedBlockNeverBecomesASecondPendingRefund() {
        executor.execute("toolu_R", json(DAMAGED_REFUND));
        executor.execute("toolu_R", json(DAMAGED_REFUND));

        assertThat(refunds.pending()).hasSize(1);
        assertThat(refunds.completedMovements()).isEmpty();
    }

    @Test
    void anUnknownOrderIsAPayload_andNothingIsQueued() {
        ToolResultPayload result = executor.execute("toolu_R",
                json("{\"order_id\":\"SF-9999\",\"amount_cents\":100,\"currency\":\"CAD\",\"reason\":\"damaged\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(json(result.content()).path("reason").asText()).isEqualTo("no_such_order");
        assertThat(refunds.pending()).isEmpty();
    }

    @Test
    void moneyShapedMistakesAreRejectedBeforeTheLedgerIsTouched() {
        RefundService guardedRefunds = mock(RefundService.class);
        InitiateRefundExecutor guarded =
                new InitiateRefundExecutor(guardedRefunds, new FakeOrderService(ToolFixtures.CLOCK));

        for (String bad : new String[] {
                // float money: 49.99 is not a count of cents, and truncating it would refund a
                // different amount than was asked for
                "{\"order_id\":\"SF-2002\",\"amount_cents\":49.99,\"currency\":\"CAD\",\"reason\":\"damaged\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":0,\"currency\":\"CAD\",\"reason\":\"damaged\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":4999,\"currency\":\"USD\",\"reason\":\"damaged\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":\"4999\",\"currency\":\"CAD\",\"reason\":\"damaged\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":4999,\"currency\":\"CAD\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":4999,\"currency\":\"CAD\",\"reason\":\"goodwill\"}",
                "{\"order_id\":\"SF-2002\",\"amount_cents\":4999,\"currency\":\"CAD\",\"reason\":\"damaged\",\"approved\":true}"}) {
            ToolResultPayload result = guarded.execute("toolu_R", json(bad));
            assertThat(result.isError()).as(bad).isTrue();
            assertThat(json(result.content()).path("error").asText()).as(bad).isEqualTo("invalid_input");
        }
        verifyNoInteractions(guardedRefunds);
    }

    @Test
    void aServiceFaultIsAnErrorResult_neverAnException() {
        RefundService broken = mock(RefundService.class);
        when(broken.requestRefund(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("ledger locked"));

        ToolResultPayload result = new InitiateRefundExecutor(broken, new FakeOrderService(ToolFixtures.CLOCK))
                .execute("toolu_R", json(DAMAGED_REFUND));

        assertThat(result.isError()).isTrue();
        assertThat(json(result.content()).path("error").asText()).isEqualTo("refund_service_unavailable");
    }
}
