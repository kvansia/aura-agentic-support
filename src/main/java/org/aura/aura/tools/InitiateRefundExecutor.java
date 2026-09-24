package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.tools.backoffice.OrderService;
import org.aura.aura.tools.backoffice.RefundService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code initiate_refund} — DESTRUCTIVE, and INERT until Day 18 (ADR-045).
 *
 * <p>Every successful execution answers {@code refund_status: "pending_confirmation"}. That is not a
 * stub standing in for the real thing; it is the real Day 17 behaviour, and the refund service has no
 * operation that moves money. The human confirmation protocol that would turn a pending request into a
 * movement ships Day 18, and until it does, the most a model can achieve through this tool is a queued
 * request a person must approve.
 *
 * <p>Idempotent on the {@code tool_use_id} for the same reason as the ticket tool — and it matters
 * more here: a replayed block must never become a second pending refund for a human to approve twice.
 */
@Slf4j
@Component
public class InitiateRefundExecutor implements ToolExecutor {

    private static final Set<String> FIELDS = Set.of("order_id", "amount_cents", "currency", "reason");
    private static final List<String> CURRENCIES = List.of("CAD");
    private static final List<String> REASONS = List.of("damaged", "not_delivered", "wrong_item", "policy_return");

    private final RefundService refunds;
    private final OrderService orders;

    public InitiateRefundExecutor(RefundService refunds, OrderService orders) {
        this.refunds = refunds;
        this.orders = orders;
    }

    @Override
    public String toolName() {
        return ToolDefinitions.INITIATE_REFUND;
    }

    @Override
    public ToolResultPayload execute(String toolUseId, JsonNode input) {
        String orderId;
        long amountCents;
        String currency;
        String reason;
        try {
            ToolInput in = ToolInput.closedObject(input, FIELDS);
            orderId = in.requiredString("order_id", ToolInput.ORDER_ID);
            amountCents = in.requiredInteger("amount_cents", 1);
            currency = in.requiredEnum("currency", CURRENCIES);
            reason = in.requiredEnum("reason", REASONS);
        } catch (ToolInput.Invalid invalid) {
            log.info("tool {} rejected input before any service call: {}", toolName(), invalid.getMessage());
            return ToolResultPayload.error("invalid_input");
        }

        try {
            if (orders.findOrder(orderId).isEmpty()) {
                ObjectNode body = ToolResultPayload.body();
                body.put("found", false);
                body.put("reason", "no_such_order");
                return ToolResultPayload.ok(body);
            }
            RefundService.RefundRequest request = refunds.requestRefund(toolUseId, orderId, amountCents,
                    currency, RefundService.Reason.valueOf(reason.toUpperCase(Locale.ROOT)));

            ObjectNode body = ToolResultPayload.body();
            body.put("refund_id", request.refundId());
            body.put("refund_status", "pending_confirmation");
            return ToolResultPayload.ok(body);
        } catch (RuntimeException fault) {
            log.warn("tool {} — refund service failed; answering is_error. tool_use_id={}", toolName(), toolUseId, fault);
            return ToolResultPayload.error("refund_service_unavailable");
        }
    }
}
