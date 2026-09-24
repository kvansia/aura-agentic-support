package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.tools.backoffice.OrderService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/** {@code get_order_status} — READ-ONLY (ADR-045). Safe to replay; ignores the tool_use id. */
@Slf4j
@Component
public class GetOrderStatusExecutor implements ToolExecutor {

    private static final Set<String> FIELDS = Set.of("order_id");

    private final OrderService orders;

    public GetOrderStatusExecutor(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public String toolName() {
        return ToolDefinitions.GET_ORDER_STATUS;
    }

    @Override
    public ToolResultPayload execute(String toolUseId, JsonNode input) {
        String orderId;
        try {
            orderId = ToolInput.closedObject(input, FIELDS).requiredString("order_id", ToolInput.ORDER_ID);
        } catch (ToolInput.Invalid invalid) {
            log.info("tool {} rejected input before any service call: {}", toolName(), invalid.getMessage());
            return ToolResultPayload.error("invalid_input");
        }

        Optional<OrderService.Order> found;
        try {
            found = orders.findOrder(orderId);
        } catch (RuntimeException fault) {
            log.warn("tool {} — order service failed; answering is_error. order_id={}", toolName(), orderId, fault);
            return ToolResultPayload.error("order_service_unavailable");
        }

        if (found.isEmpty()) {
            // A business outcome, not an execution fault: ok(), not error() — see ToolResultPayload.
            ObjectNode body = ToolResultPayload.body();
            body.put("found", false);
            body.put("reason", "no_such_order");
            return ToolResultPayload.ok(body);
        }
        return ToolResultPayload.ok(allowlisted(found.get()));
    }

    // ALLOWLIST (ADR-047): every field is named here, typed, and either an id, an enum, a date, or a
    // carrier code from our own seed. Nothing is copied wholesale off the domain object, so a field
    // added to Order later does not start flowing into the prompt until someone adds it HERE, on purpose.
    private static ObjectNode allowlisted(OrderService.Order order) {
        ObjectNode body = ToolResultPayload.body();
        body.put("found", true);
        body.put("order_id", order.orderId());
        body.put("status", order.status().name().toLowerCase());
        if (order.carrier() != null) body.put("carrier", order.carrier());
        if (order.shippedOn() != null) body.put("shipped_on", order.shippedOn().toString());
        if (order.deliveredOn() != null) body.put("delivered_on", order.deliveredOn().toString());
        ArrayNode items = body.putArray("items");
        for (OrderService.LineItem item : order.items()) {
            items.addObject().put("sku", item.sku()).put("quantity", item.quantity());
        }
        return body;
    }
}
