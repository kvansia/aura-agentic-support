package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aura.aura.tools.backoffice.FakeOrderService;
import org.aura.aura.tools.backoffice.FakeRefundService;
import org.aura.aura.tools.backoffice.FakeTicketService;
import org.aura.aura.tools.backoffice.OrderService;
import org.aura.aura.tools.backoffice.RefundService;
import org.aura.aura.tools.backoffice.TicketService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Shared, stateless builders for the Day 17 tool tests. Named neither {@code *Test} nor {@code *IT} so
 * neither Surefire nor Failsafe runs it.
 */
public final class ToolFixtures {

    private ToolFixtures() {}

    /** The fixed clock every fake is seeded from in tests: "yesterday" is always 2026-09-22. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A consistent registry over fresh, deterministically seeded fakes — boot check already run. */
    public static ToolRegistry registry() {
        return registry(new FakeOrderService(CLOCK), new FakeTicketService(), new FakeRefundService());
    }

    public static ToolRegistry registry(OrderService orders, TicketService tickets, RefundService refunds) {
        ToolRegistry registry = new ToolRegistry(List.of(
                new GetOrderStatusExecutor(orders),
                new CreateFollowupTicketExecutor(tickets, orders),
                new InitiateRefundExecutor(refunds, orders)), new ToolDefinitions());
        registry.verifyConsistency();
        return registry;
    }

    public static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("fixture is not JSON: " + raw, e);
        }
    }
}
