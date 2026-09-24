package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.tools.backoffice.OrderService;
import org.aura.aura.tools.backoffice.TicketService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code create_followup_ticket} — a WRITE, made IDEMPOTENT on the {@code tool_use_id} (ADR-045).
 *
 * <p>Scope of that idempotency, stated so nobody over-reads it: it collapses DISPATCH-LAYER replays of
 * one block. Two different blocks asking for the same thing are two tickets today; semantic
 * de-duplication is Day 18 policy. See {@code FakeTicketService}.
 */
@Slf4j
@Component
public class CreateFollowupTicketExecutor implements ToolExecutor {

    private static final Set<String> FIELDS = Set.of("order_id", "team", "summary");
    private static final List<String> TEAMS = List.of("warehouse", "shipping", "payments");
    private static final int SUMMARY_MAX = 200;

    private final TicketService tickets;
    private final OrderService orders;

    public CreateFollowupTicketExecutor(TicketService tickets, OrderService orders) {
        this.tickets = tickets;
        this.orders = orders;
    }

    @Override
    public String toolName() {
        return ToolDefinitions.CREATE_FOLLOWUP_TICKET;
    }

    @Override
    public ToolResultPayload execute(String toolUseId, JsonNode input) {
        Optional<String> orderId;
        String team;
        String summary;
        try {
            ToolInput in = ToolInput.closedObject(input, FIELDS);
            orderId = in.optionalString("order_id", ToolInput.ORDER_ID);
            team = in.requiredEnum("team", TEAMS);
            summary = in.requiredBoundedString("summary", SUMMARY_MAX);
        } catch (ToolInput.Invalid invalid) {
            log.info("tool {} rejected input before any service call: {}", toolName(), invalid.getMessage());
            return ToolResultPayload.error("invalid_input");
        }

        try {
            if (orderId.isPresent() && orders.findOrder(orderId.get()).isEmpty()) {
                ObjectNode body = ToolResultPayload.body();
                body.put("found", false);
                body.put("reason", "no_such_order");
                return ToolResultPayload.ok(body);
            }
            TicketService.FollowupTicket ticket = tickets.createFollowup(toolUseId,
                    TicketService.Team.valueOf(team.toUpperCase(Locale.ROOT)), orderId.orElse(null), summary);

            // ALLOWLIST: the summary is model-authored free text and is deliberately NOT echoed back —
            // the model already knows what it wrote, and a string field in a tool result is an
            // injection surface on the next turn (ADR-047).
            ObjectNode body = ToolResultPayload.body();
            body.put("ticket_id", ticket.ticketId());
            body.put("team", ticket.team().name().toLowerCase(Locale.ROOT));
            body.put("ticket_status", "open");
            return ToolResultPayload.ok(body);
        } catch (RuntimeException fault) {
            log.warn("tool {} — ticket service failed; answering is_error. tool_use_id={}", toolName(), toolUseId, fault);
            return ToolResultPayload.error("ticket_service_unavailable");
        }
    }
}
