package org.aura.aura.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.aura.aura.tools.backoffice.FakeOrderService;
import org.aura.aura.tools.backoffice.FakeRefundService;
import org.aura.aura.tools.backoffice.FakeTicketService;
import org.aura.aura.tools.backoffice.OrderService;
import org.aura.aura.tools.backoffice.RefundService;
import org.aura.aura.tools.backoffice.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.aura.aura.tools.ToolFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatch and the ADR-047 boot consistency check. The context tests boot a real (tiny) Spring
 * context, because the check's whole job is to make STARTUP fail — asserting on a hand-constructed
 * registry would test the method, not the property.
 */
class ToolRegistryTest {

    // ---------------------------------------------------------------- boot consistency

    @Test
    void theShippedAdvertisementAndTheExecutorBeansAgree_soTheContextStarts() {
        contextWith(new ToolDefinitions()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ToolRegistry.class)).isNotNull();
        });
    }

    @Test
    void aDeliberatelyAdvertisedFourthTool_failsStartup_namingTheDriftDirection() {
        List<ToolDefinitions.ToolSpec> drifted = new ArrayList<>(ToolDefinitions.standard());
        drifted.add(new ToolDefinitions.ToolSpec("issue_store_credit", "Issue store credit.",
                closedSchema()));

        contextWith(new ToolDefinitions(drifted)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("advertised but unregistered: [issue_store_credit]")
                    .hasMessageNotContaining("registered but unadvertised");
        });
    }

    @Test
    void anExecutorNobodyAdvertises_failsStartupTheOtherWay() {
        List<ToolDefinitions.ToolSpec> shrunk = new ArrayList<>(ToolDefinitions.standard());
        shrunk.removeIf(spec -> spec.name().equals(ToolDefinitions.INITIATE_REFUND));

        contextWith(new ToolDefinitions(shrunk)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("registered but unadvertised: [initiate_refund]");
        });
    }

    // ---------------------------------------------------------------- runtime dispatch

    @Test
    void anUnknownToolNameIsAnsweredUnknownTool_defenceInDepthPastTheBootCheck() {
        // The boot check proves we never ADVERTISE a name we cannot run. It proves nothing about what
        // the model EMITS, which is any string it likes.
        ToolResultPayload result = ToolFixtures.registry()
                .dispatch("delete_customer", "toolu_X", json("{}"));

        assertThat(result.isError()).isTrue();
        assertThat(json(result.content()).path("error").asText()).isEqualTo("unknown_tool");
    }

    @Test
    void anExecutorThatBreaksItsNeverThrowContract_isStillAnErrorResult() {
        ToolExecutor rogue = new ToolExecutor() {
            @Override public String toolName() { return ToolDefinitions.GET_ORDER_STATUS; }
            @Override public ToolResultPayload execute(String id, JsonNode input) {
                throw new IllegalStateException("bug in an executor");
            }
        };
        OrderService orders = new FakeOrderService(ToolFixtures.CLOCK);
        ToolRegistry registry = new ToolRegistry(List.of(rogue,
                new CreateFollowupTicketExecutor(new FakeTicketService(), orders),
                new InitiateRefundExecutor(new FakeRefundService(), orders)), new ToolDefinitions());

        ToolResultPayload result = registry.dispatch(ToolDefinitions.GET_ORDER_STATUS, "toolu_X",
                json("{\"order_id\":\"SF-4412\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(json(result.content()).path("error").asText()).isEqualTo("execution_failed");
    }

    // ---------------------------------------------------------------- fixtures

    private static ApplicationContextRunner contextWith(ToolDefinitions definitions) {
        return new ApplicationContextRunner()
                .withBean(ToolDefinitions.class, () -> definitions)
                .withBean(OrderService.class, () -> new FakeOrderService(ToolFixtures.CLOCK))
                .withBean(TicketService.class, FakeTicketService::new)
                .withBean(RefundService.class, FakeRefundService::new)
                .withBean(GetOrderStatusExecutor.class)
                .withBean(CreateFollowupTicketExecutor.class)
                .withBean(InitiateRefundExecutor.class)
                .withBean(ToolRegistry.class);
    }

    private static Map<String, Object> closedSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", List.of());
        schema.put("additionalProperties", false);
        return schema;
    }
}
